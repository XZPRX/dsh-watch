// 诊断用预加载：不吞掉错误，只把未捕获异常/未处理拒绝的堆栈打出来，方便定位。
process.on('uncaughtException', (error) => {
  console.error('[diag] uncaughtException:', error && error.stack ? error.stack : error)
  process.exitCode = 1
})
process.on('unhandledRejection', (reason) => {
  console.error('[diag] unhandledRejection:', reason && reason.stack ? reason.stack : reason)
  process.exitCode = 1
})
