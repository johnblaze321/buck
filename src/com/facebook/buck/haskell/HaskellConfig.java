/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.haskell;

import com.facebook.buck.rules.ToolProvider;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;

public interface HaskellConfig {

  /** @return the {@link ToolProvider} for the haskell compiler. */
  ToolProvider getCompiler();

  /** @return the {@link HaskellVersion} for the haskell compiler. */
  HaskellVersion getHaskellVersion();

  /** @return a list of flags to use for compilation. */
  ImmutableList<String> getCompilerFlags();

  /** @return the {@link ToolProvider} for the haskell linker. */
  ToolProvider getLinker();

  /** @return a list of flags to use for linking. */
  ImmutableList<String> getLinkerFlags();

  /** @return the {@link ToolProvider} for the haskell packager. */
  ToolProvider getPackager();

  /** @return whether to cache haskell link rules. */
  boolean shouldCacheLinks();

  /** @return whether to use the deprecated binary output location. */
  Optional<Boolean> shouldUsedOldBinaryOutputLocation();

  /** @return The template to use for startup scripts for GHCi targets. */
  Path getGhciScriptTemplate();

  /** @return The binutils dir for GHCi targets. */
  Path getGhciBinutils();

  /** @return The GHC binary for GHCi targets. */
  Path getGhciGhc();

  /** @return The lib dir for GHCi targets. */
  Path getGhciLib();

  /** @return The C++ compiler for GHCi targets. */
  Path getGhciCxx();

  /** @return The C compiler for GHCi targets. */
  Path getGhciCc();

  /** @return The C preprocessor for GHCi targets. */
  Path getGhciCpp();

  /** @return An optional prefix for generated Haskell package names. */
  Optional<String> getPackageNamePrefix();
}
