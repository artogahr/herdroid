{
  description = "Herdroid: native Android client for herdr";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-parts.url = "github:hercules-ci/flake-parts";
  };

  outputs =
    inputs@{ flake-parts, ... }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      systems = [
        "aarch64-darwin"
        "x86_64-linux"
        "aarch64-linux"
      ];

      perSystem =
        { system, lib, ... }:
        let
          pkgs = import inputs.nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };

          buildToolsVersion = "37.0.0";
          android = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "37.0" ];
            buildToolsVersions = [ buildToolsVersion ];
            includeEmulator = false;
            includeNDK = false;
          };
          sdkRoot = "${android.androidsdk}/libexec/android-sdk";
          jdk = pkgs.jdk17;

          env = {
            ANDROID_HOME = sdkRoot;
            ANDROID_SDK_ROOT = sdkRoot;
            JAVA_HOME = jdk.home;
          }
          // lib.optionalAttrs pkgs.stdenv.hostPlatform.isLinux {
            # Gradle's aapt2 from Maven is dynamically linked and does not run on NixOS.
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdkRoot}/build-tools/${buildToolsVersion}/aapt2";
          };

          script =
            name: text:
            pkgs.writeShellApplication {
              inherit name text;
              runtimeInputs = [
                jdk
                android.platform-tools
              ];
              runtimeEnv = env;
            };

          gradleIn = ''
            root="''${HERDROID_ROOT:-$PWD}"
            cd "$root/android"
          '';

          install = script "herdroid-install" ''
            ${gradleIn}
            ./gradlew --quiet :app:assembleDebug
            adb install -r app/build/outputs/apk/debug/app-debug.apk
            adb shell am start -n dev.herdroid/.MainActivity
          '';

          logcat = script "herdroid-logcat" ''
            pid="$(adb shell pidof dev.herdroid || true)"
            if [ -z "$pid" ]; then
              echo "dev.herdroid is not running" >&2
              exit 1
            fi
            exec adb logcat --pid="$pid" "$@"
          '';

          deviceTest = script "herdroid-device-test" ''
            ${gradleIn}
            ./gradlew :app:connectedDebugAndroidTest "$@"
          '';

          test = script "herdroid-test" ''
            ${gradleIn}
            ./gradlew test "$@"
          '';

          app = drv: {
            type = "app";
            program = lib.getExe drv;
          };
        in
        {
          devShells.default = pkgs.mkShell {
            packages = [
              jdk
              android.platform-tools
              pkgs.ktlint
              pkgs.nixfmt
            ];
            inherit env;
            shellHook = lib.concatStrings (
              lib.mapAttrsToList (k: v: "export ${k}=${lib.escapeShellArg v}\n") env
            );
          };

          apps = {
            install = app install;
            logcat = app logcat;
            test = app test;
            device-test = app deviceTest;
          };

          formatter = pkgs.nixfmt;

          checks.format =
            pkgs.runCommand "check-format"
              {
                nativeBuildInputs = [
                  pkgs.nixfmt
                  pkgs.ktlint
                ];
              }
              ''
                cd ${./.}
                nixfmt --check flake.nix
                ktlint 'android/**/*.kt' '!android/**/build/**' '!android/**/vendor/**'
                touch $out
              '';
        };
    };
}
