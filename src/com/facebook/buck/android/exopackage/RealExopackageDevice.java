/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.android.exopackage;

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.CollectingOutputReceiver;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.InstallException;
import com.facebook.buck.android.AdbHelper;
import com.facebook.buck.android.agent.util.AgentUtil;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.log.Logger;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;
import com.google.common.io.Closer;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

@VisibleForTesting
public class RealExopackageDevice implements ExopackageDevice {
  private static final Logger LOG = Logger.get(ExopackageInstaller.class);

  /** Maximum length of commands that can be passed to "adb shell". */
  private static final int MAX_ADB_COMMAND_SIZE = 1019;

  private static final Pattern LINE_ENDING = Pattern.compile("\r?\n");

  private final BuckEventBus eventBus;
  private final IDevice device;
  private final AdbHelper adbHelper;
  private final Supplier<ExopackageAgent> agent;
  private final int agentPort;

  RealExopackageDevice(
      BuckEventBus eventBus,
      IDevice device,
      AdbHelper adbHelper,
      Path agentApkPath,
      int agentPort) {
    this.eventBus = eventBus;
    this.device = device;
    this.adbHelper = adbHelper;
    this.agentPort = agentPort;
    this.agent =
        Suppliers.memoize(
            () -> ExopackageAgent.installAgentIfNecessary(eventBus, this, agentApkPath));
  }

  /**
   * Breaks a list of strings into groups whose total size is within some limit. Kind of like the
   * xargs command that groups arguments to avoid maximum argument length limits. Except that the
   * limit in adb is about 1k instead of 512k or 2M on Linux.
   */
  @VisibleForTesting
  public static ImmutableList<ImmutableList<String>> chunkArgs(
      Iterable<String> args, int sizeLimit) {
    ImmutableList.Builder<ImmutableList<String>> topLevelBuilder = ImmutableList.builder();
    ImmutableList.Builder<String> chunkBuilder = ImmutableList.builder();
    int chunkSize = 0;
    for (String arg : args) {
      if (chunkSize + arg.length() > sizeLimit) {
        topLevelBuilder.add(chunkBuilder.build());
        chunkBuilder = ImmutableList.builder();
        chunkSize = 0;
      }
      // We don't check for an individual arg greater than the limit.
      // We just put it in its own chunk and hope for the best.
      chunkBuilder.add(arg);
      chunkSize += arg.length();
    }
    ImmutableList<String> tail = chunkBuilder.build();
    if (!tail.isEmpty()) {
      topLevelBuilder.add(tail);
    }
    return topLevelBuilder.build();
  }

  @VisibleForTesting
  public static Optional<PackageInfo> parsePathAndPackageInfo(
      String packageName, String rawOutput) {
    Iterable<String> lines = Splitter.on(LINE_ENDING).omitEmptyStrings().split(rawOutput);
    String pmPathPrefix = "package:";

    String pmPath = null;
    for (String line : lines) {
      // Ignore silly linker warnings about non-PIC code on emulators
      if (!line.startsWith("WARNING: linker: ")) {
        pmPath = line;
        break;
      }
    }

    if (pmPath == null || !pmPath.startsWith(pmPathPrefix)) {
      LOG.warn("unable to locate package path for [" + packageName + "]");
      return Optional.empty();
    }

    final String packagePrefix = "  Package [" + packageName + "] (";
    final String otherPrefix = "  Package [";
    boolean sawPackageLine = false;
    final Splitter splitter = Splitter.on('=').limit(2);

    String codePath = null;
    String resourcePath = null;
    String nativeLibPath = null;
    String versionCode = null;

    for (String line : lines) {
      // Just ignore everything until we see the line that says we are in the right package.
      if (line.startsWith(packagePrefix)) {
        sawPackageLine = true;
        continue;
      }
      // This should never happen, but if we do see a different package, stop parsing.
      if (line.startsWith(otherPrefix)) {
        break;
      }
      // Ignore lines before our package.
      if (!sawPackageLine) {
        continue;
      }
      // Parse key-value pairs.
      List<String> parts = splitter.splitToList(line.trim());
      if (parts.size() != 2) {
        continue;
      }
      switch (parts.get(0)) {
        case "codePath":
          codePath = parts.get(1);
          break;
        case "resourcePath":
          resourcePath = parts.get(1);
          break;
        case "nativeLibraryPath":
          nativeLibPath = parts.get(1);
          break;
          // Lollipop uses this name.  Not sure what's "legacy" about it yet.
          // Maybe something to do with 64-bit?
          // Might need to update if people report failures.
        case "legacyNativeLibraryDir":
          nativeLibPath = parts.get(1);
          break;
        case "versionCode":
          // Extra split to get rid of the SDK thing.
          versionCode = parts.get(1).split(" ", 2)[0];
          break;
        default:
          break;
      }
    }

    if (!sawPackageLine) {
      return Optional.empty();
    }

    Preconditions.checkNotNull(codePath, "Could not find codePath");
    Preconditions.checkNotNull(resourcePath, "Could not find resourcePath");
    Preconditions.checkNotNull(nativeLibPath, "Could not find nativeLibraryPath");
    Preconditions.checkNotNull(versionCode, "Could not find versionCode");
    if (!codePath.equals(resourcePath)) {
      throw new IllegalStateException("Code and resource path do not match");
    }

    // Lollipop doesn't give the full path to the apk anymore.  Not sure why it's "base.apk".
    if (!codePath.endsWith(".apk")) {
      codePath += "/base.apk";
    }

    return Optional.of(new PackageInfo(codePath, nativeLibPath, versionCode));
  }

  @Override
  public boolean installApkOnDevice(File apk, boolean installViaSd, boolean quiet) {
    return adbHelper.installApkOnDevice(device, apk, installViaSd, quiet);
  }

  @Override
  public void stopPackage(String packageName) throws Exception {
    AdbHelper.executeCommandWithErrorChecking(device, "am force-stop " + packageName);
  }

  @Override
  public Optional<PackageInfo> getPackageInfo(String packageName) throws Exception {
    /* "dumpsys package <package>" produces output that looks like

     Package [com.facebook.katana] (4229ce68):
       userId=10145 gids=[1028, 1015, 3003]
       pkg=Package{42690b80 com.facebook.katana}
       codePath=/data/app/com.facebook.katana-1.apk
       resourcePath=/data/app/com.facebook.katana-1.apk
       nativeLibraryPath=/data/app-lib/com.facebook.katana-1
       versionCode=1640376 targetSdk=14
       versionName=8.0.0.0.23

       ...

    */
    // We call "pm path" because "dumpsys package" returns valid output if an app has been
    // uninstalled using the "--keepdata" option. "pm path", on the other hand, returns an empty
    // output in that case.
    String lines =
        AdbHelper.executeCommandWithErrorChecking(
            device, String.format("pm path %s; dumpsys package %s", packageName, packageName));

    return parsePathAndPackageInfo(packageName, lines);
  }

  @Override
  public void uninstallPackage(String packageName) throws InstallException {
    device.uninstallPackage(packageName);
  }

  @Override
  public String getSignature(String packagePath) throws Exception {
    String command = agent.get().getAgentCommand() + "get-signature " + packagePath;
    LOG.debug("Executing %s", command);
    return AdbHelper.executeCommandWithErrorChecking(device, command);
  }

  @Override
  public ImmutableSortedSet<Path> listDirRecursive(Path root) throws Exception {
    String lsOutput = AdbHelper.executeCommandWithErrorChecking(device, "ls -R " + root + " | cat");
    Set<Path> paths = new HashSet<>();
    Set<Path> dirs = new HashSet<>();
    Path currentDir = null;
    Pattern dirMatcher = Pattern.compile(":$");
    for (String line : Splitter.on(LINE_ENDING).omitEmptyStrings().split(lsOutput)) {
      if (dirMatcher.matcher(line).find()) {
        currentDir = root.relativize(Paths.get(line.substring(0, line.length() - 1)));
        dirs.add(currentDir);
      } else {
        assert currentDir != null;
        paths.add(currentDir.resolve(line));
      }
    }
    return ImmutableSortedSet.copyOf(Sets.difference(paths, dirs));
  }

  @Override
  public void rmFiles(String dirPath, Iterable<String> filesToDelete) throws Exception {
    String commandPrefix = "cd " + dirPath + " && rm ";
    // Add a fudge factor for separators and error checking.
    final int overhead = commandPrefix.length() + 100;
    for (List<String> rmArgs : chunkArgs(filesToDelete, MAX_ADB_COMMAND_SIZE - overhead)) {
      String command = commandPrefix + Joiner.on(' ').join(rmArgs);
      LOG.debug("Executing %s", command);
      AdbHelper.executeCommandWithErrorChecking(device, command);
    }
  }

  @Override
  public AutoCloseable createForward() throws Exception {
    device.createForward(agentPort, agentPort);
    return () -> {
      try {
        device.removeForward(agentPort, agentPort);
      } catch (AdbCommandRejectedException e) {
        LOG.warn(e, "Failed to remove adb forward on port %d for device %s", agentPort, device);
        eventBus.post(
            ConsoleEvent.warning(
                "Failed to remove adb forward %d. This is not necessarily a problem\n"
                    + "because it will be recreated during the next exopackage installation.\n"
                    + "See the log for the full exception.",
                agentPort));
      }
    };
  }

  @Override
  public void installFile(final Path targetDevicePath, final Path source) throws Exception {
    Preconditions.checkArgument(source.isAbsolute());
    Preconditions.checkArgument(targetDevicePath.isAbsolute());
    Closer closer = Closer.create();
    FileInstallReceiver receiver = new FileInstallReceiver(closer, source);

    String targetFileName = targetDevicePath.toString();
    String command =
        "umask 022 && "
            + agent.get().getAgentCommand()
            + "receive-file "
            + agentPort
            + " "
            + Files.size(source)
            + " "
            + targetFileName
            + " ; echo -n :$?";
    LOG.debug("Executing %s", command);

    // If we fail to execute the command, stash the exception.  My experience during development
    // has been that the exception from checkReceiverOutput is more actionable.
    Exception shellException = null;
    try {
      device.executeShellCommand(command, receiver);
    } catch (Exception e) {
      shellException = e;
    }

    // Close the client socket, if we opened it.
    closer.close();

    if (receiver.getError().isPresent()) {
      Exception prev = shellException;
      shellException = receiver.getError().get();
      if (prev != null) {
        shellException.addSuppressed(prev);
      }
    }

    try {
      AdbHelper.checkReceiverOutput(command, receiver);
    } catch (Exception e) {
      if (shellException != null) {
        e.addSuppressed(shellException);
      }
      throw e;
    }

    if (shellException != null) {
      throw shellException;
    }

    // The standard Java libraries on Android always create new files un-readable by other users.
    // We use the shell user or root to create these files, so we need to explicitly set the mode
    // to allow the app to read them.  Ideally, the agent would do this automatically, but
    // there's no easy way to do this in Java.  We can drop this if we drop support for the
    // Java agent.
    AdbHelper.executeCommandWithErrorChecking(device, "chmod 644 " + targetFileName);
  }

  @Override
  public void mkDirP(String dirpath) throws Exception {
    // Kind of a hack here.  The java agent can't force the proper permissions on the
    // directories it creates, so we use the command-line "mkdir -p" instead of the java agent.
    // Fortunately, "mkdir -p" seems to work on all devices where we use use the java agent.
    String mkdirCommand = agent.get().getMkDirCommand();

    AdbHelper.executeCommandWithErrorChecking(
        device, "umask 022 && " + mkdirCommand + " " + dirpath);
  }

  @Override
  public String getProperty(String name) throws Exception {
    return AdbHelper.executeCommandWithErrorChecking(device, "getprop " + name).trim();
  }

  @Override
  public List<String> getDeviceAbis() throws Exception {
    ImmutableList.Builder<String> abis = ImmutableList.builder();
    // Rare special indigenous to Lollipop devices
    String abiListProperty = getProperty("ro.product.cpu.abilist");
    if (!abiListProperty.isEmpty()) {
      abis.addAll(Splitter.on(',').splitToList(abiListProperty));
    } else {
      String abi1 = getProperty("ro.product.cpu.abi");
      if (abi1.isEmpty()) {
        throw new RuntimeException("adb returned empty result for ro.product.cpu.abi property.");
      }

      abis.add(abi1);
      String abi2 = getProperty("ro.product.cpu.abi2");
      if (!abi2.isEmpty()) {
        abis.add(abi2);
      }
    }

    return abis.build();
  }

  @Override
  public void killProcess(String processName) throws Exception {
    String packageName =
        processName.contains(":")
            ? processName.substring(0, processName.indexOf(':'))
            : processName;
    AdbHelper.executeCommandWithErrorChecking(
        device, String.format("run-as %s killall %s", packageName, processName));
  }

  private class FileInstallReceiver extends CollectingOutputReceiver {
    private final Closer closer;
    private final Path source;
    private boolean startedPayload;
    private boolean wrotePayload;
    @Nullable private OutputStream outToDevice;
    private Optional<Exception> error;

    public FileInstallReceiver(Closer closer, Path source) {
      this.closer = closer;
      this.source = source;
      this.startedPayload = false;
      this.wrotePayload = false;
      this.error = Optional.empty();
    }

    @Override
    public void addOutput(byte[] data, int offset, int length) {
      super.addOutput(data, offset, length);
      // On exceptions, we want to still collect the full output of the command (so we can get its
      // error code and possibly error message), so we just record that there was an error and only
      // send further output to the base receiver.
      if (error.isPresent()) {
        return;
      }
      try {
        if (!startedPayload && getOutput().length() >= AgentUtil.TEXT_SECRET_KEY_SIZE) {
          LOG.verbose("Got key: %s", getOutput().split("[\\r\\n]", 1)[0]);
          startedPayload = true;
          Socket clientSocket = new Socket("127.0.0.1", agentPort); // NOPMD
          closer.register(clientSocket);
          LOG.verbose("Connected");
          outToDevice = clientSocket.getOutputStream();
          closer.register(outToDevice);
          // Need to wait for client to acknowledge that we've connected.
        }
        if (outToDevice == null) {
          throw new NullPointerException();
        }
        if (!wrotePayload && getOutput().contains("z1")) {
          if (outToDevice == null) {
            throw new NullPointerException("outToDevice was null when protocol says it cannot be");
          }
          LOG.verbose("Got z1");
          wrotePayload = true;
          outToDevice.write(getOutput().substring(0, AgentUtil.TEXT_SECRET_KEY_SIZE).getBytes());
          LOG.verbose("Wrote key");
          com.google.common.io.Files.asByteSource(source.toFile()).copyTo(outToDevice);
          outToDevice.flush();
          LOG.verbose("Wrote file");
        }
      } catch (IOException e) {
        error = Optional.of(e);
      }
    }

    public Optional<Exception> getError() {
      return error;
    }
  }
}
