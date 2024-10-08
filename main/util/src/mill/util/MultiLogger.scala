package mill.util

import mill.api.{Logger, SystemStreams}

import java.io.{InputStream, OutputStream, PrintStream}

class MultiLogger(
    val colored: Boolean,
    val logger1: Logger,
    val logger2: Logger,
    val inStream0: InputStream,
    override val debugEnabled: Boolean
) extends Logger {
  override def toString: String = s"MultiLogger($logger1, $logger2)"
  lazy val systemStreams = new SystemStreams(
    new MultiStream(logger1.systemStreams.out, logger2.systemStreams.out),
    new MultiStream(logger1.systemStreams.err, logger2.systemStreams.err),
    inStream0
  )

  def info(s: String): Unit = {
    logger1.info(s)
    logger2.info(s)
  }
  def error(s: String): Unit = {
    logger1.error(s)
    logger2.error(s)
  }
  def ticker(s: String): Unit = {
    logger1.ticker(s)
    logger2.ticker(s)
  }

  override def setPromptDetail(key: Seq[String], s: String): Unit = {
    logger1.setPromptDetail(key, s)
    logger2.setPromptDetail(key, s)
  }

  private[mill] override def setPromptLine(
      key: Seq[String],
      verboseKeySuffix: String,
      message: String
  ): Unit = {
    logger1.setPromptLine(key, verboseKeySuffix, message)
    logger2.setPromptLine(key, verboseKeySuffix, message)
  }

  private[mill] override def setPromptLine(): Unit = {
    logger1.setPromptLine()
    logger2.setPromptLine()
  }

  def debug(s: String): Unit = {
    logger1.debug(s)
    logger2.debug(s)
  }

  override def close(): Unit = {
    logger1.close()
    logger2.close()
  }
  private[mill] override def reportKey(key: Seq[String]): Unit = {
    logger1.reportKey(key)
    logger2.reportKey(key)
  }

  override def rawOutputStream: PrintStream = systemStreams.out

  private[mill] override def removePromptLine(key: Seq[String]): Unit = {
    logger1.removePromptLine(key)
    logger2.removePromptLine(key)
  }
  private[mill] override def removePromptLine(): Unit = {
    logger1.removePromptLine()
    logger2.removePromptLine()
  }
  private[mill] override def setPromptLeftHeader(s: String): Unit = {
    logger1.setPromptLeftHeader(s)
    logger2.setPromptLeftHeader(s)
  }

  override def withPromptPaused[T](t: => T): T =
    logger1.withPromptPaused(logger2.withPromptPaused(t))

  override def enableTicker: Boolean = logger1.enableTicker || logger2.enableTicker

  override def subLogger(path: os.Path, key: String, message: String): Logger = {
    new MultiLogger(
      colored,
      logger1.subLogger(path, key, message),
      logger2.subLogger(path, key, message),
      inStream0,
      debugEnabled
    )
  }
}

class MultiStream(stream1: OutputStream, stream2: OutputStream)
    extends PrintStream(new OutputStream {
      def write(b: Int): Unit = {
        stream1.write(b)
        stream2.write(b)
      }
      override def write(b: Array[Byte]): Unit = {
        stream1.write(b)
        stream2.write(b)
      }
      override def write(b: Array[Byte], off: Int, len: Int) = {
        stream1.write(b, off, len)
        stream2.write(b, off, len)
      }
      override def flush() = {
        stream1.flush()
        stream2.flush()
      }
      override def close() = {
        stream1.close()
        stream2.close()
      }
    })
