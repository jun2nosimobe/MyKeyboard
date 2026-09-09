"""
TeXコマンド入力(EnglishDictionaryHelper / eng_dict.db, type=1)の
並び順を「実際によく使われる順」に付け直し、抜けていた頻出コマンドを追加するスクリプト。

背景:
    これまでのweightは実質「コマンド名の文字数で機械的に10刻みにグルーピングし、
    同グループ内はSQLiteのTEXT比較(=ほぼアルファベット順)」という、使用頻度とは
    無関係な並びだった(例: \\AA や \\aa のような超マイナー記号が \\frac \\sqrt と
    同グループに来る一方、\\infty や \\equiv が中位グループに埋もれる、など)。

    このスクリプトは「数学記法として実際によく使うか」を基準に手動でTIERを組み、
    それに基づいてweightを振り直す。ORDER BY weight ASCなので、数値が小さいほど
    候補の上位に出る。

使い方:
    python tools/build_tex_commands.py
    (app/src/main/assets/eng_dict.db を直接更新する。実行後は
     DictionaryDatabaseHelper.ktやMatrixManager.ktと同じ要領で
     EnglishDictionaryHelper.ASSET_DICT_VERSION を+1し、
     外部ステージング(adb push)も忘れずに)

メンテナンス:
    新しいTeXコマンドを追加/優先度を変えたい場合は、下のTIERS配列を編集して
    再実行すればよい。TIERSに出てこない既存コマンドはweight=500(中位)、
    JUNK(Khan Academy固有の色マクロや内部プラミング等、実質使われない記号)は
    weight=900に落ちる。
"""
import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "eng_dict.db"

# 優先度の高い順(weight=0が最優先)。同じコマンドが複数TIERに出た場合は
# 最初に出てきたTIER(=より優先度が高い方)が採用される。
TIERS: list[list[str]] = [
    # TIER 0 (weight=0): 数式を書くなら必ずと言っていいほど使う最頻出コマンド
    [
        r"\frac", r"\sqrt", r"\sum", r"\int", r"\infty", r"\leq", r"\geq", r"\neq",
        r"\approx", r"\in", r"\forall", r"\exists", r"\rightarrow", r"\times", r"\cdot",
        r"\pm", r"\partial", r"\nabla", r"\alpha", r"\beta", r"\gamma", r"\delta", r"\pi",
        r"\sigma", r"\theta", r"\sin", r"\cos", r"\tan", r"\log", r"\ln", r"\lim",
        r"\max", r"\min", r"\left", r"\right", r"\text",
    ],
    # TIER 1 (weight=10): 上に次いで頻出
    [
        r"\Rightarrow", r"\Leftrightarrow", r"\leftrightarrow", r"\subset", r"\subseteq",
        r"\supset", r"\notin", r"\cup", r"\cap", r"\setminus", r"\emptyset",
        r"\mathbb", r"\mathcal", r"\mathbf", r"\mathrm", r"\begin", r"\end",
        r"\binom", r"\overline", r"\underline", r"\hat", r"\bar", r"\vec", r"\tilde",
        r"\dot", r"\epsilon", r"\lambda", r"\mu", r"\phi", r"\omega",
        r"\Delta", r"\Sigma", r"\Omega", r"\det", r"\dim",
    ],
    # TIER 2 (weight=20): よく使う
    [
        r"\Gamma", r"\Lambda", r"\Phi", r"\Psi", r"\Theta", r"\Xi", r"\eta", r"\tau",
        r"\xi", r"\nu", r"\rho", r"\chi", r"\psi", r"\iota", r"\kappa", r"\zeta",
        r"\sec", r"\csc", r"\cot", r"\sinh", r"\cosh", r"\tanh", r"\exp", r"\arg",
        r"\deg", r"\gcd", r"\lcm", r"\ker", r"\hom", r"\sup", r"\inf", r"\equiv",
        r"\cong", r"\simeq", r"\propto", r"\perp", r"\parallel", r"\angle", r"\triangle",
        r"\wedge", r"\vee",
    ],
    # TIER 3 (weight=30): まあまあ使う
    [
        r"\neg", r"\land", r"\lor", r"\iff", r"\implies", r"\impliedby", r"\mapsto",
        r"\circ", r"\bullet", r"\cdots", r"\ldots", r"\vdots", r"\ddots", r"\prod",
        r"\coprod", r"\bigcup", r"\bigcap", r"\bigoplus", r"\bigotimes", r"\bigvee",
        r"\bigwedge", r"\oplus", r"\otimes", r"\ominus", r"\odot", r"\bigodot",
        r"\sqcup", r"\sqcap", r"\wr", r"\ast", r"\star", r"\dagger", r"\ddagger",
        r"\top", r"\bot", r"\aleph", r"\hbar", r"\ell", r"\Re", r"\Im", r"\varnothing",
    ],
    # TIER 4 (weight=40): 特定分野でよく使う
    [
        r"\overrightarrow", r"\overleftarrow", r"\widehat", r"\widetilde",
        r"\overbrace", r"\underbrace", r"\boxed", r"\choose", r"\operatorname",
        r"\overset", r"\underset", r"\stackrel", r"\nexists", r"\exist", r"\prime",
        r"\backslash", r"\langle", r"\rangle", r"\lceil", r"\rceil", r"\lfloor",
        r"\rfloor", r"\vert", r"\Vert", r"\mid", r"\sim", r"\approxeq", r"\asymp",
        r"\doteq", r"\prec", r"\succ", r"\preceq", r"\succeq", r"\ll", r"\gg",
        r"\gtrsim", r"\lesssim", r"\gtrapprox", r"\lessapprox", r"\subsetneq",
        r"\supsetneq", r"\nsubseteq", r"\nsupseteq",
    ],
    # TIER 5 (weight=50): 特定分野・やや専門的
    [
        r"\varepsilon", r"\varphi", r"\varpi", r"\varrho", r"\varsigma", r"\vartheta",
        r"\digamma", r"\eth", r"\daleth", r"\gimel", r"\beth", r"\complement",
        r"\Bbbk", r"\wp", r"\S", r"\P", r"\natural", r"\sharp", r"\flat",
        r"\therefore", r"\because", r"\colon", r"\vdash", r"\dashv", r"\models",
        r"\nvdash", r"\nvDash", r"\vDash", r"\Vdash", r"\multimap", r"\rtimes",
        r"\ltimes", r"\bowtie", r"\pitchfork", r"\frown", r"\smile", r"\between",
        r"\bumpeq", r"\Doteq", r"\liminf", r"\limsup",
    ],
    # TIER 6 (weight=60): 行列・環境・スペーシング系
    [
        r"\pmatrix", r"\bmatrix", r"\vmatrix", r"\Vmatrix", r"\cases", r"\substack",
        r"\dfrac", r"\tfrac", r"\cfrac", r"\qquad", r"\quad", r"\hspace",
        r"\phantom", r"\hphantom", r"\vphantom", r"\displaystyle", r"\textstyle",
        r"\scriptstyle", r"\scriptscriptstyle", r"\boldsymbol", r"\mathfrak",
        r"\mathscr", r"\mathsf", r"\mathtt", r"\mathit", r"\pmb",
        r"\big", r"\Big", r"\bigg", r"\Bigg", r"\middle", r"\limits", r"\nolimits",
    ],
    # TIER 7 (weight=70): 矢印いろいろ
    [
        r"\longrightarrow", r"\longleftarrow", r"\longleftrightarrow",
        r"\Longrightarrow", r"\Longleftarrow", r"\Longleftrightarrow",
        r"\hookrightarrow", r"\hookleftarrow", r"\twoheadrightarrow",
        r"\twoheadleftarrow", r"\rightarrowtail", r"\leftarrowtail",
        r"\nearrow", r"\searrow", r"\swarrow", r"\nwarrow",
        r"\upharpoonleft", r"\upharpoonright", r"\downharpoonleft", r"\downharpoonright",
        r"\leftharpoonup", r"\leftharpoondown", r"\rightharpoonup", r"\rightharpoondown",
        r"\leftrightharpoons", r"\rightleftharpoons", r"\leftleftarrows",
        r"\rightrightarrows", r"\leftrightarrows", r"\rightleftarrows",
        r"\Uparrow", r"\Downarrow", r"\Updownarrow", r"\uparrow", r"\downarrow",
        r"\updownarrow", r"\circlearrowleft", r"\circlearrowright",
        r"\curvearrowleft", r"\curvearrowright",
    ],
    # TIER 8 (weight=80): 記号いろいろ・文書系
    [
        r"\clubsuit", r"\diamondsuit", r"\heartsuit", r"\spadesuit", r"\square",
        r"\blacksquare", r"\triangleleft", r"\triangleright", r"\trianglelefteq",
        r"\trianglerighteq", r"\blacktriangle", r"\blacktriangledown",
        r"\circledR", r"\circledS", r"\checkmark", r"\maltese", r"\sphericalangle",
        r"\measuredangle", r"\degree", r"\bigstar", r"\lozenge", r"\diamond",
        r"\Diamond", r"\newcommand", r"\renewcommand", r"\providecommand",
        r"\notag", r"\nonumber", r"\eqref", r"\nleq", r"\ngeq", r"\nless", r"\ngtr",
        r"\ne", r"\le", r"\ge", r"\to", r"\gets",
    ],
]

# 実質使われない・Khan Academy(KaTeX)固有の色マクロ・内部プラミング等 → 最下位
JUNK: list[str] = [
    r"\redA", r"\redB", r"\redC", r"\redD", r"\redE", r"\red",
    r"\blueA", r"\blueB", r"\blueC", r"\blueD", r"\blueE", r"\blue",
    r"\greenA", r"\greenB", r"\greenC", r"\greenD", r"\greenE", r"\green",
    r"\goldA", r"\goldB", r"\goldC", r"\goldD", r"\goldE",
    r"\grayA", r"\grayB", r"\grayC", r"\grayD", r"\grayE", r"\grayF", r"\grayG",
    r"\grayH", r"\grayI", r"\gray",
    r"\purpleA", r"\purpleB", r"\purpleC", r"\purpleD", r"\purpleE", r"\purple",
    r"\tealA", r"\tealB", r"\tealC", r"\tealD", r"\tealE",
    r"\maroonA", r"\maroonB", r"\maroonC", r"\maroonD", r"\maroonE",
    r"\pink", r"\orange", r"\kaBlue", r"\kaGreen", r"\mintA", r"\mintB", r"\mintC",
    r"\DOTSB", r"\DOTSI", r"\DOTSX", r"\TextOrMath", r"\errmessage", r"\noexpand",
    r"\expandafter", r"\message", r"\show", r"\bgroup", r"\egroup", r"\nobreak",
    r"\nobreakspace", r"\allowbreak", r"\tmspace", r"\negthickspace", r"\negmedspace",
    r"\negthinspace", r"\mathstrut", r"\rule", r"\llap", r"\rlap", r"\clap", r"\smash",
    r"\ordinarycolon", r"\simcolon", r"\simcoloncolon", r"\minuscolon",
    r"\minuscoloncolon", r"\equalscolon", r"\equalscoloncolon", r"\coloncolon",
    r"\coloncolonequals", r"\coloncolonminus", r"\coloncolonsim", r"\coloncolonapprox",
    r"\approxcolon", r"\approxcoloncolon", r"\colonapprox", r"\colonsim",
    r"\colonequals", r"\colonminus", r"\Coloneq", r"\Coloneqq", r"\Colonsim",
    r"\Colonapprox", r"\Eqcolon", r"\Eqqcolon", r"\eqcolon", r"\eqqcolon",
    r"\dblcolon", r"\vcentcolon", r"\ratio", r"\natnums", r"\cnums", r"\Reals",
    r"\reals", r"\real", r"\Complex", r"\image", r"\imageof", r"\origof",
    r"\plim", r"\injlim", r"\projlim", r"\varinjlim", r"\varprojlim",
    r"\varliminf", r"\varlimsup",
]

# 現行DBに存在しない(=これまで抜けていた)頻出コマンド。TIERSで既に触れているものは
# INSERT時にそのweightが使われる。TIERSに出てこないがここで新規追加するものは無し
# (全てTIERSのどこかに含めてある)。
NEW_COMMANDS = [
    r"\left", r"\right", r"\binom", r"\overline", r"\underline",
    r"\overrightarrow", r"\overleftarrow", r"\widehat", r"\widetilde",
    r"\lcm", r"\operatorname", r"\overset", r"\underset", r"\stackrel",
    r"\choose", r"\boldsymbol", r"\cfrac", r"\pmb",
    r"\big", r"\Big", r"\bigg", r"\Bigg", r"\middle", r"\limits", r"\nolimits",
    r"\notag", r"\nonumber", r"\eqref",
]

DEFAULT_WEIGHT = 500  # TIERSにもJUNKにも出てこない既存コマンドの既定順位
JUNK_WEIGHT = 900


def build_weight_map(existing_words: set[str]) -> dict[str, int]:
    weight_map: dict[str, int] = {}
    for tier_index, words in enumerate(TIERS):
        w = tier_index * 10
        for word in words:
            if word not in weight_map:
                weight_map[word] = w
    for word in JUNK:
        if word not in weight_map:
            weight_map[word] = JUNK_WEIGHT

    # TIERS/JUNKで言及されなかった既存コマンドはデフォルト順位
    for word in existing_words:
        if word not in weight_map:
            weight_map[word] = DEFAULT_WEIGHT
    return weight_map


def main() -> None:
    conn = sqlite3.connect(str(DB_PATH))
    cur = conn.cursor()

    cur.execute("SELECT word FROM english_math_dictionary WHERE type = 1")
    existing_words = {row[0] for row in cur.fetchall()}

    weight_map = build_weight_map(existing_words)

    updated = 0
    for word in existing_words:
        cur.execute(
            "UPDATE english_math_dictionary SET weight = ? WHERE word = ? AND type = 1",
            (weight_map[word], word),
        )
        updated += 1

    inserted = 0
    for word in NEW_COMMANDS:
        if word in existing_words:
            continue
        w = weight_map.get(word, DEFAULT_WEIGHT)
        cur.execute(
            "INSERT OR REPLACE INTO english_math_dictionary (word, type, weight) VALUES (?, 1, ?)",
            (word, w),
        )
        inserted += 1

    conn.commit()

    # 見出し違反(TIERSにtypoで存在しないコマンドを書いた場合)を検出
    all_tier_words = {w for tier in TIERS for w in tier} | set(JUNK)
    unknown = sorted(w for w in all_tier_words if w not in existing_words and w not in NEW_COMMANDS)
    if unknown:
        print(f"[warn] TIERS/JUNKにあるがDBにもNEW_COMMANDSにも無い単語 ({len(unknown)}件):")
        for w in unknown:
            print(f"  {w}")

    print(f"updated={updated} inserted={inserted}")
    conn.close()


if __name__ == "__main__":
    main()
