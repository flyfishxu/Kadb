package com.flyfishxu.kadb.io

import okio.FileSystem

// Okio exposes SYSTEM only in its non-JS platform source sets, not common metadata.
internal expect val platformFileSystem: FileSystem
