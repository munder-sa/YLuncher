package com.gt8d.launcher.notice

enum class NoticeAction(val label: String) {
    EditSets("セットを編集"), Home("ホームへ"), ChooseBrowser("ブラウザを選ぶ")
}

sealed interface Notice {
    val text: String
    val action: NoticeAction?

    data class SplitUnsupported(val appLabel: String) : Notice {
        override val text = "${appLabel}は分割に対応していないため、全画面で開きました"
        override val action = NoticeAction.EditSets
    }
    data class SplitFailed(val appLabel: String) : Notice {
        override val text = "分割で開けませんでした。${appLabel}を全画面で開きました"
        override val action = NoticeAction.Home
    }
    data class OpenFailed(val appLabel: String) : Notice {
        override val text = "${appLabel}を開けませんでした"
        override val action = NoticeAction.Home
    }
    data class AppMissing(val appLabel: String) : Notice {
        override val text = "${appLabel}が見つかりません"
        override val action = NoticeAction.EditSets
    }
    data object Slow : Notice {
        override val text = "開くのに時間がかかっています"
        override val action: NoticeAction? = null
    }
    data object NoBrowser : Notice {
        override val text = "リンクを開けるブラウザがありません"
        override val action = NoticeAction.ChooseBrowser
    }
    data object BrowserFailed : Notice {
        override val text = "ブラウザを起動できませんでした"
        override val action: NoticeAction? = null
    }
}
