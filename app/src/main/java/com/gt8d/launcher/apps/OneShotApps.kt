package com.gt8d.launcher.apps

// 押すと用事を済ませて一瞬で閉じるアプリ。パネルの相手(分割の反対側のアプリ)として記録しない。
object OneShotApps {
    const val BACK_CAR = "com.ds.backcar" // 端末の Car(CarPlay から車の元の画面に戻る)
    private val packages = setOf(BACK_CAR)
    fun contains(pkg: String?): Boolean = pkg != null && pkg in packages
}
