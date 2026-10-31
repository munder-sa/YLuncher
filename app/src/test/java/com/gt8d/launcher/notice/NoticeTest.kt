package com.gt8d.launcher.notice

import org.junit.Assert.assertEquals
import org.junit.Test

class NoticeTest {
    @Test fun textsAndActions() {
        val cases = listOf(
            Triple(Notice.SplitUnsupported("動画"), "動画は分割に対応していないため、全画面で開きました", NoticeAction.EditSets),
            Triple(Notice.SplitFailed("動画"), "分割で開けませんでした。動画を全画面で開きました", NoticeAction.Home),
            Triple(Notice.OpenFailed("動画"), "動画を開けませんでした", NoticeAction.Home),
            Triple(Notice.AppMissing("動画"), "動画が見つかりません", NoticeAction.EditSets),
            Triple(Notice.Slow, "開くのに時間がかかっています", null),
            Triple(Notice.NoBrowser, "リンクを開けるブラウザがありません", NoticeAction.ChooseBrowser),
            Triple(Notice.BrowserFailed, "ブラウザを起動できませんでした", null),
        )
        cases.forEach { (notice, text, action) ->
            assertEquals(text, notice.text)
            assertEquals(action, notice.action)
        }
        assertEquals(listOf("セットを編集", "ホームへ", "ブラウザを選ぶ"), NoticeAction.entries.map { it.label })
    }
}
