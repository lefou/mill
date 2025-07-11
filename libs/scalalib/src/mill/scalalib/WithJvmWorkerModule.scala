package mill.scalalib

import mill.T
import mill.define.{Module, ModuleRef, PathRef, Task}

/**
 * Common trait for modules that use either a custom or a globally shared [[JvmWorkerModule]].
 */
trait WithJvmWorkerModule extends JavaHomeModule {
  def jvmWorker: ModuleRef[JvmWorkerModule] = ModuleRef(JvmWorkerModule)

}
