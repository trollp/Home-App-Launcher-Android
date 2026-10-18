package com.petertroll.quiethome

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherResumeContractTest {
    private val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/petertroll/quiethome/LauncherActivity.kt").exists() }

    @Test fun `resuming launcher always resets to a clean home screen`() {
        val source = File(
            root,
            "app/src/main/java/com/petertroll/quiethome/LauncherActivity.kt"
        ).readText()
        val onResume = Regex(
            "override fun onResume\\(\\) \\{([\\s\\S]*?)\\n    \\}",
        ).find(source)?.groupValues?.get(1).orEmpty()

        assertTrue(onResume.contains("showHome(clearSearch = true)"))

        val showHome = Regex(
            "private fun showHome\\(clearSearch: Boolean\\) \\{([\\s\\S]*?)\\n    \\}",
        ).find(source)?.groupValues?.get(1).orEmpty()
        assertTrue(showHome.contains("if (clearSearch) binding.search.setText(\"\")"))
        assertTrue(showHome.contains("binding.search.clearFocus()"))
        assertTrue(showHome.contains("binding.drawerPanel.isVisible = false"))
        assertTrue(showHome.contains("binding.homePanel.isVisible = true"))
        assertTrue(showHome.contains("binding.bottomQuickActions.isVisible = true"))
        assertTrue(showHome.contains("hideSoftInputFromWindow"))
    }
}