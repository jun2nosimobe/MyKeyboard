package com.example.mykeyboard

// 🌟 フリック入力用の1キーが持つ5方向(タップ+上下左右)の文字
data class FlickKeyData(
    val center: String,
    val up: String? = null,
    val down: String? = null,
    val left: String? = null,
    val right: String? = null
) {
    fun forDirection(direction: TouchEventHandler.FlickDirection): String? = when (direction) {
        TouchEventHandler.FlickDirection.UP -> up
        TouchEventHandler.FlickDirection.DOWN -> down
        TouchEventHandler.FlickDirection.LEFT -> left
        TouchEventHandler.FlickDirection.RIGHT -> right
        TouchEventHandler.FlickDirection.NONE -> null
    }
}

object FlickKeyDatabase {
    // 標準的な12キーフリック配列: タップ=あ段、左=い段、上=う段、右=え段、下=お段
    val keys = mapOf(
        R.id.flick_a to FlickKeyData(center = "あ", left = "い", up = "う", right = "え", down = "お"),
        R.id.flick_ka to FlickKeyData(center = "か", left = "き", up = "く", right = "け", down = "こ"),
        R.id.flick_sa to FlickKeyData(center = "さ", left = "し", up = "す", right = "せ", down = "そ"),
        R.id.flick_ta to FlickKeyData(center = "た", left = "ち", up = "つ", right = "て", down = "と"),
        R.id.flick_na to FlickKeyData(center = "な", left = "に", up = "ぬ", right = "ね", down = "の"),
        R.id.flick_ha to FlickKeyData(center = "は", left = "ひ", up = "ふ", right = "へ", down = "ほ"),
        R.id.flick_ma to FlickKeyData(center = "ま", left = "み", up = "む", right = "め", down = "も"),
        // 🌟 や行は本来3音(や・ゆ・よ)しかなく左右が空いていたので、括弧を割り当てる
        R.id.flick_ya to FlickKeyData(center = "や", up = "ゆ", down = "よ", left = "(", right = ")"),
        R.id.flick_ra to FlickKeyData(center = "ら", left = "り", up = "る", right = "れ", down = "ろ"),
        // 🌟 わ行: タップ=わ、左=を、上=ん、右=ー、下=〜 (ユーザー指定の配置)
        R.id.flick_wa to FlickKeyData(center = "わ", left = "を", up = "ん", right = "ー", down = "〜")
    )

    // 🌟 句読点キー: タップ=。、左=、、上=？、右=！ (一般的なフリック配列の並びに準拠)
    val punctKey = FlickKeyData(center = "。", left = "、", up = "？", right = "！")

    // 🌟 数字モードの句読点キー(半角): 数式入力では全角より半角の方が使いやすいとの要望
    val numPunctKey = FlickKeyData(center = ".", left = ",", up = "?", right = "!")

    // 🌟 数字グリッドの各キーにフリックで記号を割り当てる(ユーザー指定のレイアウト)。
    // テンキーの物理配置(1-9が3x3、0が下段中央)を意識した割り当てになっている。
    val numberKeys: Map<Int, FlickKeyData> = mapOf(
        R.id.flick_num1 to FlickKeyData(center = "1", up = "+", down = "-", left = "*", right = "/"),
        R.id.flick_num2 to FlickKeyData(center = "2", up = "'", down = "@", left = "{", right = "}"),
        R.id.flick_num3 to FlickKeyData(center = "3", up = ":", down = ";", left = "[", right = "]"),
        R.id.flick_num4 to FlickKeyData(center = "4", up = "$", down = "=", left = "\\", right = "#"),
        // 🌟 5はテンキーの中心なので方向キー(↑↓←→)を割り当てる
        R.id.flick_num5 to FlickKeyData(center = "5", up = "↑", down = "↓", left = "←", right = "→"),
        R.id.flick_num6 to FlickKeyData(center = "6", up = "|", down = "~", left = "<", right = ">"),
        R.id.flick_num7 to FlickKeyData(center = "7", up = "↔", down = "⇒", left = "⇔", right = "↷"),
        R.id.flick_num8 to FlickKeyData(center = "8", up = "^", down = "_", left = "(", right = ")"),
        R.id.flick_num9 to FlickKeyData(center = "9", up = "%", down = "°", left = "∞", right = "√"),
        R.id.flick_num0 to FlickKeyData(center = "0", up = ".", down = ",", left = "'", right = "\"")
    )

    // 🌟 「゛゜」キーを連続タップした時に巡回させる濁点/半濁点/小文字サイクル。
    // ガラケー時代からの標準的な挙動(同じキーを連打して切り替える)を踏襲。
    val dakutenCycle: Map<Char, Char> = mapOf(
        'か' to 'が', 'が' to 'か',
        'き' to 'ぎ', 'ぎ' to 'き',
        'く' to 'ぐ', 'ぐ' to 'く',
        'け' to 'げ', 'げ' to 'け',
        'こ' to 'ご', 'ご' to 'こ',
        'さ' to 'ざ', 'ざ' to 'さ',
        'し' to 'じ', 'じ' to 'し',
        'す' to 'ず', 'ず' to 'す',
        'せ' to 'ぜ', 'ぜ' to 'せ',
        'そ' to 'ぞ', 'ぞ' to 'そ',
        'た' to 'だ', 'だ' to 'た',
        'ち' to 'ぢ', 'ぢ' to 'ち',
        'つ' to 'っ', 'っ' to 'づ', 'づ' to 'つ',
        'て' to 'で', 'で' to 'て',
        'と' to 'ど', 'ど' to 'と',
        'は' to 'ば', 'ば' to 'ぱ', 'ぱ' to 'は',
        'ひ' to 'び', 'び' to 'ぴ', 'ぴ' to 'ひ',
        'ふ' to 'ぶ', 'ぶ' to 'ぷ', 'ぷ' to 'ふ',
        'へ' to 'べ', 'べ' to 'ぺ', 'ぺ' to 'へ',
        'ほ' to 'ぼ', 'ぼ' to 'ぽ', 'ぽ' to 'ほ',
        'う' to 'ゔ', 'ゔ' to 'ぅ', 'ぅ' to 'う',
        'あ' to 'ぁ', 'ぁ' to 'あ',
        'い' to 'ぃ', 'ぃ' to 'い',
        'え' to 'ぇ', 'ぇ' to 'え',
        'お' to 'ぉ', 'ぉ' to 'お',
        'や' to 'ゃ', 'ゃ' to 'や',
        'ゆ' to 'ゅ', 'ゅ' to 'ゆ',
        'よ' to 'ょ', 'ょ' to 'よ',
        'わ' to 'ゎ', 'ゎ' to 'わ'
    )
}
