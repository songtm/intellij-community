/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.execution;

import com.intellij.execution.junit.JUnitUtil;
import com.intellij.execution.junit.TestClassFilter;
import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.application.ReadActionProcessor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.persistent.PersistentFS;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiElementProcessor;
import com.intellij.psi.search.PsiElementProcessorAdapter;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.search.searches.ClassesWithAnnotatedMembersSearch;
import com.intellij.psi.stubsHierarchy.impl.ClassAnchorUtil;
import com.intellij.psi.stubsHierarchy.impl.HierarchyService;
import com.intellij.psi.stubsHierarchy.impl.SingleClassHierarchy;
import com.intellij.psi.stubsHierarchy.impl.SmartClassAnchor;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.util.Processor;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public class ConfigurationUtil {
  // return true if there is JUnit4 test
  public static boolean findAllTestClasses(final TestClassFilter testClassFilter, final Set<PsiClass> found) {
    final PsiManager manager = testClassFilter.getPsiManager();

    final Project project = manager.getProject();
    GlobalSearchScope projectScopeWithoutLibraries = GlobalSearchScope.projectScope(project);
    final GlobalSearchScope scope = projectScopeWithoutLibraries.intersectWith(testClassFilter.getScope());

    SingleClassHierarchy symbols = HierarchyService.isEnabled() ? HierarchyService.instance(project).getSingleClassHierarchy() : null;

    if (symbols != null) {
      SmartClassAnchor[] candidates = symbols.getAllSubtypes(testClassFilter.getBase());
      for (SmartClassAnchor candidate : candidates) {
        int fileId = candidate.myFileId;
        VirtualFile file = PersistentFS.getInstance().findFileById(fileId);
        if (file != null && scope.contains(file)) {
          PsiClass aClass = ClassAnchorUtil.retrieveInReadAction(project, candidate);
          if (testClassFilter.isAccepted(aClass)) {
            found.add(aClass);
          }
        }
      }
    }
    else {
      ClassInheritorsSearch.search(testClassFilter.getBase(), scope, true, true, false).forEach(new PsiElementProcessorAdapter<PsiClass>(new PsiElementProcessor<PsiClass>() {
        public boolean execute(@NotNull final PsiClass aClass) {
          if (testClassFilter.isAccepted(aClass)) found.add(aClass);
          return true;
        }
      }));
    }

    // classes having suite() method
    final PsiMethod[] suiteMethods = ApplicationManager.getApplication().runReadAction(
        new Computable<PsiMethod[]>() {
          public PsiMethod[] compute() {
            return PsiShortNamesCache.getInstance(project).getMethodsByName(JUnitUtil.SUITE_METHOD_NAME, scope);
          }
        }
    );
    for (final PsiMethod method : suiteMethods) {
      ApplicationManager.getApplication().runReadAction(new Runnable() {
        public void run() {
          final PsiClass containingClass = method.getContainingClass();
          if (containingClass == null) return;
          if (containingClass instanceof PsiAnonymousClass) return;
          if (containingClass.hasModifierProperty(PsiModifier.ABSTRACT)) return;
          if (containingClass.getContainingClass() != null && !containingClass.hasModifierProperty(PsiModifier.STATIC)) return;
          if (JUnitUtil.isSuiteMethod(method) && testClassFilter.isAccepted(containingClass)) {
            found.add(containingClass);
          }
        }
      });
    }

    Set<PsiClass> processed = ContainerUtil.newHashSet();
    boolean hasJunit4 = addAnnotatedMethodsAnSubclasses(manager, scope, testClassFilter, found, processed, JUnitUtil.TEST_ANNOTATION, true, symbols);
    hasJunit4 |= addAnnotatedMethodsAnSubclasses(manager, scope, testClassFilter, found, processed, JUnitUtil.TEST5_ANNOTATION, true, symbols);
    hasJunit4 |= addAnnotatedMethodsAnSubclasses(manager, scope, testClassFilter, found, processed, JUnitUtil.RUN_WITH, false, symbols);
    return hasJunit4;
  }

  private static boolean addAnnotatedMethodsAnSubclasses(final PsiManager manager, final GlobalSearchScope scope, final TestClassFilter testClassFilter,
                                                         final Set<PsiClass> found,
                                                         final Set<PsiClass> processed,
                                                         final String annotation,
                                                         final boolean isMethod,
                                                         final SingleClassHierarchy table) {
    final Ref<Boolean> isJUnit4 = new Ref<Boolean>(Boolean.FALSE);
    // annotated with @Test
    final PsiClass testAnnotation = ApplicationManager.getApplication().runReadAction(
        new Computable<PsiClass>() {
          @Nullable
          public PsiClass compute() {
            return JavaPsiFacade.getInstance(manager.getProject()).findClass(annotation, GlobalSearchScope.allScope(manager.getProject()));
          }
        }
    );
    if (testAnnotation != null) {
      //allScope is used to find all abstract test cases which probably have inheritors in the current 'scope'
      ClassesWithAnnotatedMembersSearch.search(testAnnotation, GlobalSearchScope.allScope(manager.getProject())).forEach(new Processor<PsiClass>() {
        public boolean process(final PsiClass annotated) {
          AccessToken token = ReadAction.start();
          try {
            if (!processed.add(annotated)) { // don't process the same class twice regardless of it being in the scope
              return true;
            }
            final VirtualFile file = PsiUtilCore.getVirtualFile(annotated);
            if (file != null && scope.contains(file) && testClassFilter.isAccepted(annotated)) {
              if (!found.add(annotated)) {
                return true;
              }
              isJUnit4.set(Boolean.TRUE);
            }
          }
          finally {
            token.finish();
          }
          if (table != null) {
            SmartClassAnchor[] candidates = table.getAllSubtypes(annotated);
            for (final SmartClassAnchor candidate : candidates) {
              ApplicationManager.getApplication().runReadAction(new Runnable() {
                @Override
                public void run() {
                  int fileId = candidate.myFileId;
                  VirtualFile file = PersistentFS.getInstance().findFileById(fileId);
                  if (file != null && scope.contains(file)) {
                    PsiClass aClass = ClassAnchorUtil.retrieve(manager.getProject(), candidate);
                    if (testClassFilter.isAccepted(aClass)) {
                      found.add(aClass);
                      processed.add(aClass);
                      isJUnit4.set(Boolean.TRUE);
                    }
                  }
                }
              });
            }
          }
          else {
            ClassInheritorsSearch.search(annotated, scope, true, true, false).forEach(new ReadActionProcessor<PsiClass>() {
              @Override
              public boolean processInReadAction(PsiClass aClass) {
                if (testClassFilter.isAccepted(aClass)) {
                  found.add(aClass);
                  processed.add(aClass);
                  isJUnit4.set(Boolean.TRUE);
                }
                return true;
              }
            });
          }
          return true;
        }
      });
    }

    return isJUnit4.get().booleanValue();
  }
}
