/*
 * Copyright 2010-2015 JetBrains s.r.o.
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

package org.jetbrains.kotlin.idea.references

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.AbstractProjectComponent
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.util.Function
import com.intellij.util.PathUtil
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.asJava.LightClassUtil
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.context.*
import org.jetbrains.kotlin.context.MutableModuleContext
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.frontend.di.*
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.JetFile
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.BindingTraceContext
import org.jetbrains.kotlin.resolve.DescriptorToSourceUtils
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.TargetPlatform
import org.jetbrains.kotlin.resolve.lazy.ResolveSession
import org.jetbrains.kotlin.resolve.lazy.declarations.FileBasedDeclarationProviderFactory
import org.jetbrains.kotlin.resolve.scopes.JetScope
import org.jetbrains.kotlin.types.DynamicTypesSettings
import org.jetbrains.kotlin.utils.*

import java.io.File
import java.net.MalformedURLException
import java.net.URL
import java.util.HashSet

import org.jetbrains.kotlin.resolve.descriptorUtil.classId
import org.jetbrains.kotlin.serialization.deserialization.findClassAcrossModuleDependencies

public class BuiltInsReferenceResolver(val project: Project, val startupManager: StartupManager) {

    volatile private var moduleDescriptor: ModuleDescriptor? = null
    volatile public var builtInsSources: Set<JetFile>? = null
        private set
    volatile private var builtinsPackageFragment: PackageFragmentDescriptor? = null

    init {
        startupManager.runWhenProjectIsInitialized { initialize() }
    }

    private fun initialize() {
        assert(moduleDescriptor == null, "Attempt to initialize twice")

        val jetBuiltInsFiles = getJetBuiltInsFiles()

        val initializeRunnable = { ->
            val newModuleContext = ContextForNewModule(project, Name.special("<built-ins resolver module>"), ModuleParameters.Empty)
            newModuleContext.setDependencies(newModuleContext.module)

            val declarationFactory = FileBasedDeclarationProviderFactory(newModuleContext.storageManager, jetBuiltInsFiles)

            val resolveSession = createLazyResolveSession(
                    newModuleContext,
                    declarationFactory, BindingTraceContext(),
                    TargetPlatform.Default)

            newModuleContext.initializeModuleContents(resolveSession.getPackageFragmentProvider())

            if (!ApplicationManager.getApplication().isUnitTestMode()) {
                // Use lazy initialization in tests
                resolveSession.forceResolveAll()
            }

            val packageView = newModuleContext.module.getPackage(KotlinBuiltIns.BUILT_INS_PACKAGE_FQ_NAME)
            val fragments = packageView.fragments

            this@BuiltInsReferenceResolver.moduleDescriptor = newModuleContext.module
            builtinsPackageFragment = fragments.single()
            builtInsSources = jetBuiltInsFiles
        }

        if (ApplicationManager.getApplication().isUnitTestMode()) {
            initializeRunnable()
        }
        else {
            ApplicationManager.getApplication().executeOnPooledThread {
                runReadAction { initializeRunnable() }
            }
        }
    }

    private fun getJetBuiltInsFiles(): Set<JetFile> {
        val builtIns = getBuiltInSourceFiles(LightClassUtil.getBuiltInsDirUrl())

        if (ApplicationManager.getApplication().isUnitTestMode()) {
            // In production, the above URL is enough as it contains sources for both native and compilable built-ins
            // (it's simply the "kotlin" directory in kotlin-plugin.jar)
            // But in tests, sources of built-ins are not added to the classpath automatically, so we manually specify URLs for both:
            // LightClassUtil.getBuiltInsDirUrl() does so for native built-ins and the code below for compilable built-ins
            try {
                return builtIns + getBuiltInSourceFiles(BUILT_INS_COMPILABLE_SRC_DIR.toURI().toURL())
            }
            catch (e: MalformedURLException) {
                throw rethrow(e)
            }
        }

        return builtIns
    }

    private fun getBuiltInSourceFiles(url: URL): Set<JetFile> {
        val fromUrl = VfsUtilCore.convertFromUrl(url)
        val vf = VirtualFileManager.getInstance().findFileByUrl(fromUrl)
        assert(vf != null) { "Virtual file not found by URL: $url" }

        // Refreshing VFS: in case the plugin jar was updated, the caches may hold the old value
        vf!!.getChildren()
        vf.refresh(false, true)
        PathUtil.getLocalFile(vf).refresh(false, true)

        val psiDirectory = PsiManager.getInstance(project).findDirectory(vf)
        assert(psiDirectory != null) { "No PsiDirectory for $vf" }
        return psiDirectory!!.getFiles().filterIsInstance<JetFile>().toHashSet()
    }

    private fun findCurrentDescriptorForMember(originalDescriptor: MemberDescriptor): DeclarationDescriptor? {
        if (!isFromBuiltinModule(originalDescriptor)) return null

        val containingDeclaration = findCurrentDescriptor(originalDescriptor.getContainingDeclaration())
        val memberScope = getMemberScope(containingDeclaration) ?: return null

        val renderedOriginal = DescriptorRenderer.FQ_NAMES_IN_TYPES.render(originalDescriptor)
        val descriptors: Collection<DeclarationDescriptor>
        if (originalDescriptor is ConstructorDescriptor && containingDeclaration is ClassDescriptor) {
            descriptors = containingDeclaration.getConstructors()
        }
        else {
            descriptors = memberScope.getAllDescriptors()
        }

        return descriptors.firstOrNull { renderedOriginal == DescriptorRenderer.FQ_NAMES_IN_TYPES.render(it) }
    }

    private fun findCurrentDescriptor(originalDescriptor: DeclarationDescriptor): DeclarationDescriptor? = when(originalDescriptor) {
        is ClassDescriptor -> {
            if (isFromBuiltinModule(originalDescriptor))
                moduleDescriptor!!.findClassAcrossModuleDependencies(originalDescriptor.classId)
            else
                null
        }

        is PackageFragmentDescriptor -> {
            if (isFromBuiltinModule(originalDescriptor) && KotlinBuiltIns.BUILT_INS_PACKAGE_FQ_NAME == originalDescriptor.fqName)
                builtinsPackageFragment
            else
                null
        }

        is MemberDescriptor -> findCurrentDescriptorForMember(originalDescriptor)

        else -> null
    }

    public fun resolveBuiltInSymbol(declarationDescriptor: DeclarationDescriptor): PsiElement? {
        if (moduleDescriptor == null) {
            return null
        }

        val descriptor = findCurrentDescriptor(declarationDescriptor)
        if (descriptor != null) {
            return DescriptorToSourceUtils.getSourceFromDescriptor(descriptor)
        }
        return null
    }

    companion object {
        private val BUILT_INS_COMPILABLE_SRC_DIR = File("core/builtins/src", KotlinBuiltIns.BUILT_INS_PACKAGE_NAME.asString())

        public fun getInstance(project: Project): BuiltInsReferenceResolver =
            ServiceManager.getService(project, javaClass<BuiltInsReferenceResolver>())

        private fun isFromBuiltinModule(originalDescriptor: DeclarationDescriptor): Boolean {
            // TODO This is optimization only
            // It should be rewritten by checking declarationDescriptor.getSource(), when the latter returns something non-trivial for builtins.
            return KotlinBuiltIns.getInstance().getBuiltInsModule() == DescriptorUtils.getContainingModule(originalDescriptor)
        }

        public fun isFromBuiltIns(element: PsiElement): Boolean =
                getInstance(element.project).builtInsSources!!.contains(element.getContainingFile())

        private fun getMemberScope(parent: DeclarationDescriptor?): JetScope? = when(parent) {
            is ClassDescriptor -> parent.getDefaultType().getMemberScope()
            is PackageFragmentDescriptor -> parent.getMemberScope()
            else -> null
        }
    }
}
