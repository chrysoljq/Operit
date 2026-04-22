const SandboxPackageDevInstallerState = {
  logs: []
};

const SandboxPackageDevInstaller = (function () {
  const TOOL_TYPE = "default";
  const ENVIRONMENT = "android";
  const SKILL_NAME = "SandboxPackage_DEV";
  const SKILL_ROOT = `/sdcard/Download/Operit/skills/${SKILL_NAME}`;
  const REFERENCES_DIR = `${SKILL_ROOT}/references`;
  const TYPES_DIR = `${SKILL_ROOT}/types`;
  const SCRIPTS_DIR = `${SKILL_ROOT}/scripts`;
  const EXAMPLES_DIR = `${SKILL_ROOT}/examples`;
  const EXAMPLE_PACKAGES_DIR = `${EXAMPLES_DIR}/packages`;
  const BUILTIN_PACKAGES_ASSET_DIR = "packages";
  const CDN_BASE = "https://cdn.jsdelivr.net/gh/AAswordman/Operit@main";
  const TYPE_FILES = [
    "android.d.ts",
    "chat.d.ts",
    "compose-dsl.d.ts",
    "compose-dsl.material3.generated.d.ts",
    "core.d.ts",
    "cryptojs.d.ts",
    "ffmpeg.d.ts",
    "files.d.ts",
    "index.d.ts",
    "java-bridge.d.ts",
    "jimp.d.ts",
    "memory.d.ts",
    "network.d.ts",
    "okhttp.d.ts",
    "pako.d.ts",
    "quickjs-runtime.d.ts",
    "results.d.ts",
    "software_settings.d.ts",
    "system.d.ts",
    "tasker.d.ts",
    "tool-types.d.ts",
    "toolpkg.d.ts",
    "ui.d.ts",
    "workflow.d.ts"
  ];

  const DOWNLOADS = [
    {
      url: `${CDN_BASE}/docs/SCRIPT_DEV_SKILL.md`,
      destination: `${SKILL_ROOT}/SKILL.md`
    },
    {
      url: `${CDN_BASE}/docs/SCRIPT_DEV_GUIDE.md`,
      destination: `${REFERENCES_DIR}/SCRIPT_DEV_GUIDE.md`
    },
    {
      url: `${CDN_BASE}/docs/TOOLPKG_FORMAT_GUIDE.md`,
      destination: `${REFERENCES_DIR}/TOOLPKG_FORMAT_GUIDE.md`
    }
  ].concat(
    TYPE_FILES.map((fileName) => ({
      url: `${CDN_BASE}/examples/types/${fileName}`,
      destination: `${TYPES_DIR}/${fileName}`
    }))
  );

  function logStep(message) {
    SandboxPackageDevInstallerState.logs.push(message);
    console.log(message);
  }

  function parseToolResult(toolName, rawText) {
    let parsed;
    try {
      parsed = JSON.parse(String(rawText ?? ""));
    } catch (error) {
      throw new Error(`${toolName} returned non-JSON result: ${String(error && error.message ? error.message : error)}`);
    }
    if (!parsed || parsed.success !== true) {
      const detail =
        (parsed && (parsed.error || parsed.message)) ||
        `Unknown ${toolName} failure`;
      throw new Error(`${toolName} failed: ${String(detail)}`);
    }
    return parsed;
  }

  function callTool(toolName, params) {
    const rawText = NativeInterface.callTool(TOOL_TYPE, toolName, JSON.stringify(params || {}));
    return parseToolResult(toolName, rawText);
  }

  function makeDirectory(path) {
    return callTool("make_directory", {
      path,
      create_parents: "true",
      environment: ENVIRONMENT
    });
  }

  function downloadFile(url, destination) {
    return callTool("download_file", {
      url,
      destination,
      environment: ENVIRONMENT
    });
  }

  async function downloadFileAsync(url, destination) {
    const result = await toolCall(TOOL_TYPE, "download_file", {
      url,
      destination,
      environment: ENVIRONMENT
    });

    if (!result || result.success !== true) {
      const detail =
        (result && (result.error || result.message)) ||
        `Unknown download_file failure for ${destination}`;
      throw new Error(String(detail));
    }

    return result;
  }

  async function downloadAllFiles() {
    await Promise.all(
      DOWNLOADS.map(async (item) => {
        logStep(`Downloading -> ${item.destination}`);
        await downloadFileAsync(item.url, item.destination);
      })
    );
  }

  function collectRelativeFiles(directory, relativePrefix, collectedFiles) {
    const children = directory.listFiles();
    if (!children) {
      return;
    }

    for (let index = 0; index < children.length; index += 1) {
      const child = children[index];
      const relativePath = relativePrefix
        ? `${relativePrefix}/${String(child.getName())}`
        : String(child.getName());

      if (child.isDirectory()) {
        collectRelativeFiles(child, relativePath, collectedFiles);
        continue;
      }

      collectedFiles.push(relativePath);
    }
  }

  function syncBuiltInPackageExamples() {
    const File = Java.type("java.io.File");
    const AssetCopyUtils = Java.type("com.ai.assistance.operit.util.AssetCopyUtils");
    const context = Java.getApplicationContext();
    const outputDir = new File(EXAMPLE_PACKAGES_DIR);
    const copiedFiles = [];

    AssetCopyUtils.INSTANCE.copyAssetDirRecursive(
      context,
      BUILTIN_PACKAGES_ASSET_DIR,
      outputDir,
      true
    );

    collectRelativeFiles(outputDir, "", copiedFiles);
    copiedFiles.sort();
    return copiedFiles;
  }

  async function run() {
    logStep(`Preparing skill root -> ${SKILL_ROOT}`);
    makeDirectory("/sdcard/Download/Operit/skills");
    makeDirectory(SKILL_ROOT);
    makeDirectory(REFERENCES_DIR);
    makeDirectory(TYPES_DIR);
    makeDirectory(SCRIPTS_DIR);
    makeDirectory(EXAMPLES_DIR);
    await downloadAllFiles();

    logStep(`Syncing built-in package examples -> ${EXAMPLE_PACKAGES_DIR}`);
    const copiedExampleFiles = syncBuiltInPackageExamples();
    logStep(`Built-in package examples synced -> ${copiedExampleFiles.length} files`);

    return {
      success: true,
      message: `${SKILL_NAME} installed or updated successfully.`,
      data: {
        skill_name: SKILL_NAME,
        skill_root: SKILL_ROOT,
        references_dir: REFERENCES_DIR,
        types_dir: TYPES_DIR,
        scripts_dir: SCRIPTS_DIR,
        examples_dir: EXAMPLES_DIR,
        examples_packages_dir: EXAMPLE_PACKAGES_DIR,
        downloaded_count: DOWNLOADS.length,
        type_count: TYPE_FILES.length,
        builtin_example_count: copiedExampleFiles.length,
        builtin_example_files: copiedExampleFiles,
        logs: SandboxPackageDevInstallerState.logs
      }
    };
  }

  return {
    run
  };
})();

SandboxPackageDevInstaller.run()
  .then((result) => {
    complete(result);
  })
  .catch((error) => {
    complete({
      success: false,
      message: String(error && error.message ? error.message : error),
      data: {
        skill_name: "SandboxPackage_DEV",
        logs: SandboxPackageDevInstallerState.logs
      }
    });
  });
