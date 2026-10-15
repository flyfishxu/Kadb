package com.flyfishxu.kadb.exception

import okio.IOException

class AdbPairAuthException : IOException("Pairing required or trust missing")
