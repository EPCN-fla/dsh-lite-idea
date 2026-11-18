package com.dshlite.idea.bridge

import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class BridgeInstallScriptTest {
    private fun fixture(): Pair<Path, Path> {
        val home = Files.createTempDirectory("dsh bridge ' home ")
        val bin = Files.createDirectory(home.resolve("bin"))
        val dsh = bin.resolve("dsh")
        Files.writeString(dsh, """#!/bin/bash
if [[ " ${'$'}* " == *" --dump-config "* ]]; then
  mkdir -p "${'$'}DSH_HOME/profiles/acp-vscode"
  printf '%s\n' '{"dsh":{"profile":{"bundles":[]}}}' > "${'$'}DSH_HOME/profiles/acp-vscode/package.json"
  exit 0
fi
if [ "${'$'}1" = plugin ]; then
  printf '%s\n' "${'$'}*" >> "${'$'}DSH_HOME/plugin-calls"
  [ "${'$'}FAIL_PLUGIN" != 1 ]
  exit ${'$'}?
fi
exit 0
""")
        dsh.toFile().setExecutable(true)
        return home to bin
    }

    private fun run(home: Path, bin: Path, failPlugin: Boolean = false): String {
        return runWithEnvironment(home, bin, failPlugin)
    }

    private fun runWithEnvironment(home: Path, bin: Path, failPlugin: Boolean): String {
        val builder = ProcessBuilder("/bin/bash", "-s", "--", "dsh-vscode-bridge@0.5.2")
            .redirectErrorStream(true)
        builder.environment()["DSH_HOME"] = home.toString()
        builder.environment()["PATH"] = bin.toString() + ":" + builder.environment()["PATH"].orEmpty()
        if (failPlugin) builder.environment()["FAIL_PLUGIN"] = "1"
        val process = builder.start()
        process.outputStream.bufferedWriter().use { it.write(BridgeInstall.buildInstallScript("dsh")) }
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(output, process.waitFor(20, TimeUnit.SECONDS))
        if (!failPlugin) assertEquals(output, 0, process.exitValue())
        return output
    }

    @Test fun `fresh install and repair converge to two stable activation rows`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val (home, bin) = fixture()
        run(home, bin)
        val patch = home.resolve("profiles/acp-vscode/cordis.patch.yml")
        val first = Files.readString(patch)
        assertTrue(first.contains("name: '@deepseek-ai/dsh-workspace'"))
        assertTrue(first.contains("name: 'dsh-vscode-bridge'"))
        assertFalse(first.contains("agent-preset"))
        run(home, bin)
        assertEquals(first, Files.readString(patch))
        assertEquals(2, Files.readAllLines(home.resolve("plugin-calls")).size)
        assertTrue(Files.readString(home.resolve("profiles/acp-vscode/package.json"))
            .contains("@deepseek-ai/dsh-acp-app"))
    }

    @Test fun `package failure leaves an existing activation patch untouched`() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val (home, bin) = fixture()
        val profile = Files.createDirectories(home.resolve("profiles/acp-vscode"))
        Files.writeString(profile.resolve("package.json"), "{\"dsh\":{\"profile\":{\"bundles\":[]}}}")
        val patch = profile.resolve("cordis.patch.yml")
        Files.writeString(patch, "# user patch\n[]\n")
        run(home, bin, failPlugin = true)
        assertEquals("# user patch\n[]\n", Files.readString(patch))
        assertTrue(Files.list(profile).noneMatch { it.fileName.toString().contains("bridge-install") })
    }
}
