package com.qm.qqzygisk.hook.parasitic

import org.junit.Assert.*
import org.junit.Test

class ModuleResourceGuardTest {
    private class ResourceState(var readable: Boolean = false) {
        var injections = 0
        fun ensure() = ensureModuleResource(
            isReadable = { readable },
            attach = { injections++; readable = true; 1 },
        )
    }

    @Test
    fun `the resources used by Compose are injected even if Activity resources were already injected`() {
        val activity = ResourceState()
        activity.ensure()
        val compositionContext = ResourceState()
        compositionContext.ensure()
        assertTrue(activity.readable)
        assertTrue(compositionContext.readable)
        assertEquals(1, activity.injections)
        assertEquals(1, compositionContext.injections)
    }

    @Test
    fun `a replaced backing implementation is checked again on the same Resources object`() {
        val resources = ResourceState()
        resources.ensure()
        resources.readable = false
        resources.ensure()
        assertTrue(resources.readable)
        assertEquals(2, resources.injections)
    }

    @Test
    fun `already available module resources do not require another asset path`() {
        val resources = ResourceState(true)
        resources.ensure()
        assertEquals(0, resources.injections)
    }

    @Test
    fun `zero asset cookie is a loading failure instead of success`() {
        assertThrows(IllegalStateException::class.java) {
            ensureModuleResource({ false }, { 0 })
        }
    }

    @Test
    fun `positive asset cookie alone cannot hide a missing module color`() {
        assertThrows(IllegalStateException::class.java) {
            ensureModuleResource({ false }, { 1 })
        }
    }
}
