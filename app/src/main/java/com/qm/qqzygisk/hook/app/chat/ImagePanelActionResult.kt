package com.qm.qqzygisk.hook.app.chat

internal fun performImagePanelAction(
    send: () -> Result<Unit>,
    recordUsage: () -> Unit,
    onUsageFailure: (Throwable) -> Unit,
): Result<Unit> {
    val result = runCatching(send).getOrElse { Result.failure(it) }
    if (result.isSuccess) {
        runCatching(recordUsage).onFailure(onUsageFailure)
    }
    return result
}
