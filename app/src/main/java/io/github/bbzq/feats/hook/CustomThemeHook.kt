package io.github.bbzq.feats.hook

import android.app.AlertDialog
import android.graphics.Color
import android.util.SparseArray
import android.view.View
import android.widget.EditText
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.allFields
import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.fieldOrNull
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.newInstanceOrNull
import io.github.bbzq.feats.replace
import io.github.bbzq.feats.setBooleanField
import io.github.bbzq.feats.setIntField
import io.github.bbzq.feats.setObjectField
import io.github.bbzq.feats.symbol.RestoredCustomSkinSymbols
import io.github.bbzq.feats.symbol.RestoredCustomThemeSymbols
import org.json.JSONObject
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * Adds two virtual theme entries to Bilibili, following the custom-theme flow used by
 * BiliRoaming while keeping all settings in BBZQ's remote preferences.
 */
class CustomThemeHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        val customSkinEnabled = ModuleSettings.isCustomSkinEnabled(prefs)
        // A portable garb contains its own colors. It must win over this module's
        // virtual color-theme feature, otherwise its reset hook can undo the garb.
        val customColorEnabled = ModuleSettings.isCustomThemeEnabled(prefs) && !customSkinEnabled
        if (customSkinEnabled) {
            CustomSkinApplier.applyIfChanged(env)
            val skinSymbols = env.symbols?.customSkin?.restore(classLoader)
            if (skinSymbols == null) {
                log("Custom skin resolver missing; using broadcast fallback")
            } else {
                hookSkinResolver(skinSymbols.resolverMethod)
                // 皮肤响应替换(load_equip / user_equip)与播放页图标(play_icon)注入:
                // 使用独立的 customSkin 符号,不再依赖颜色主题扫描(customTheme),
                // 这样即使颜色主题符号缺失,皮肤功能依然完整。
                hookSkinResponse(skinSymbols)
                hookPlayIcon(skinSymbols)
            }
            // Suppressing the reset also stops an already-equipped theme from overriding
            // the imported skin when MainActivity restores it on startup.
            val themeSymbols = env.symbols?.customTheme?.restore(classLoader)
            if (themeSymbols != null) suppressThemeReset(themeSymbols)
        }
        if (!customColorEnabled) {
            log("startHook: customSkin=$customSkinEnabled customColor=$customColorEnabled")
            return
        }
        val symbols = env.symbols?.customTheme?.restore(classLoader) ?: run {
            log("CustomTheme skipped: symbols missing or failed to restore")
            return
        }
        val initialColor = ModuleSettings.getCustomThemeColor(prefs)
        if (customColorEnabled) {
            installThemeMaps(symbols, initialColor)
            hookThemeList(symbols)
            hookThemeClick(symbols)
        }
        suppressThemeReset(symbols)
        log("startHook: customColor=$customColorEnabled customSkin=$customSkinEnabled resetMethods=${symbols.themeResetMethods.size}")
    }

    /**
     * Bilibili re-applies the equipped theme during [MAIN_ACTIVITY].onPostCreate, which would
     * override the injected color theme or imported skin. Skip the reset only on that path so
     * our garb wins while leaving genuine user-initiated theme switches untouched.
     */
    private fun suppressThemeReset(symbols: RestoredCustomThemeSymbols) {
        symbols.themeResetMethods.forEach { method ->
            env.replace(method) { param ->
                if (Thread.currentThread().stackTrace.any {
                        it.className == MAIN_ACTIVITY && it.methodName == "onPostCreate"
                    }
                ) {
                    null
                } else {
                    param.invokeOriginalMethod()
                }
            }
        }
    }

    private fun hookSkinResolver(resolveMethod: java.lang.reflect.Method) {
        env.replace(resolveMethod) { param ->
            if (!ModuleSettings.isCustomSkinEnabled(prefs)) {
                return@replace param.invokeOriginalMethod()
            }
            val skin = customSkinEquip() ?: return@replace param.invokeOriginalMethod()
            parseHostJson(skin.toString(), resolveMethod.returnType) ?: param.invokeOriginalMethod()
        }
        log("Custom skin resolver hook installed: ${resolveMethod.declaringClass.name}.${resolveMethod.name}")
    }

    /**
     * Substitute the skin model while Bilibili parses /x/resource/show/skin.
     * Thus every official refresh receives the imported equip before it can update UI/state.
     */
    private fun hookSkinResponse(skinSymbols: RestoredCustomSkinSymbols) {
        val userGarbSetter = skinSymbols.skinResponseUserGarbSetter ?: run {
            if (skinSymbols.skinResolveMethod == null) log("Custom skin response hooks unavailable; using broadcast fallback")
            return
        }
        env.hookBefore(userGarbSetter) { param ->
            if (!ModuleSettings.isCustomSkinEnabled(prefs)) return@hookBefore
            val skin = customSkinEquip() ?: return@hookBefore
            val replacement = parseHostJson(skin.toString(), userGarbSetter.parameterTypes[0]) ?: return@hookBefore
            param.args[0] = replacement
        }
        skinSymbols.skinResponseLoadEquipSetter?.let { loadEquipSetter ->
            env.hookBefore(loadEquipSetter) { param ->
                if (!ModuleSettings.isCustomSkinEnabled(prefs)) return@hookBefore
                val loadEquip = customSkinConfig()?.optJSONObject("load_equip") ?: return@hookBefore
                val replacement = parseHostJson(loadEquip.toString(), loadEquipSetter.parameterTypes[0]) ?: return@hookBefore
                param.args[0] = replacement
            }
        }
        log("Custom skin response hook installed: ${userGarbSetter.declaringClass.name}")
    }

    /**
     * 播放页进度条拖动图标(play_icon)注入:
     * 自制主题 JSON 里的 drag_left_png / drag_right_png / middle_png
     * 是进度条控件左拉、右拉、不拉三种状态的表现图,挂载在
     * ViewReply.getPlayerIcon() 的返回对象上。这里 hook 该 getter,
     * 在 B 站读取播放页详情时返回自制的 PlayerIcon。可选增强。
     */
    private fun hookPlayIcon(skinSymbols: RestoredCustomSkinSymbols) {
        val playerIconGetter = skinSymbols.playerIconGetter ?: run {
            log("Custom skin play icon hook skipped: playerIcon getter missing")
            return
        }
        env.hookAfter(playerIconGetter) { param ->
            if (!ModuleSettings.isCustomSkinEnabled(prefs)) return@hookAfter
            val playIcon = customSkinConfig()?.optJSONObject("play_icon") ?: return@hookAfter
            if (playIcon.optString("drag_left_png").isBlank() &&
                playIcon.optString("drag_right_png").isBlank() &&
                playIcon.optString("middle_png").isBlank()
            ) return@hookAfter
            buildPlayerIcon(playIcon)?.let { param.result = it }
        }
        log("Custom skin play icon hook installed: ${playerIconGetter.declaringClass.name}.${playerIconGetter.name}")
    }

    /**
     * 通过反射构造 B 站 PlayerIcon 对象(protobuf 生成类):
     * PlayerIcon.newBuilder().setDragLeftPng(url).setDragRightPng(url).setMiddlePng(url).build()
     */
    private fun buildPlayerIcon(playIcon: JSONObject): Any? = runCatching {
        val playerIconClass = classLoader.loadClass(PLAYER_ICON_CLASS)
        val builder = playerIconClass.getMethod("newBuilder").invoke(null)
        val builderClass = builder.javaClass
        playIcon.optString("drag_left_png").takeIf { it.isNotBlank() }?.let {
            builderClass.getMethod("setDragLeftPng", String::class.java).invoke(builder, it)
        }
        playIcon.optString("drag_right_png").takeIf { it.isNotBlank() }?.let {
            builderClass.getMethod("setDragRightPng", String::class.java).invoke(builder, it)
        }
        playIcon.optString("middle_png").takeIf { it.isNotBlank() }?.let {
            builderClass.getMethod("setMiddlePng", String::class.java).invoke(builder, it)
        }
        builderClass.getMethod("build").invoke(builder)
    }.getOrNull()

    private fun customSkinConfig(): JSONObject? = runCatching {
        val raw = ModuleSettings.getCustomSkinJson(prefs)
        if (raw.isBlank()) return null
        JSONObject(raw)
    }.getOrNull()

    private fun customSkinEquip(): JSONObject? {
        val root = customSkinConfig() ?: return null
        return (root.optJSONObject("user_equip") ?: root).takeIf { it.optLong("id") > 0L }
    }

    private fun parseHostJson(json: String, targetClass: Class<*>): Any? {
        val jsonClass = sequenceOf("com.alibaba.fastjson.JSON", "com.alibaba.fastjson2.JSON")
            .mapNotNull { name -> runCatching { classLoader.loadClass(name) }.getOrNull() }
            .firstOrNull()
            ?: return null
        val parseObject = jsonClass.methods.firstOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.name == "parseObject" &&
                method.parameterTypes.contentEquals(arrayOf(String::class.java, Class::class.java))
        } ?: return null
        return runCatching { parseObject.invoke(null, json, targetClass) }.getOrNull()
    }

    /** The web process has its own color tables and does not run the theme list UI. */
    fun insertColorForWebProcess() {
        if (!ModuleSettings.isCustomThemeEnabled(prefs)) return
        val symbols = env.symbols?.customTheme?.restore(classLoader) ?: return
        val color = ModuleSettings.getCustomThemeColor(prefs)
        installThemeMaps(symbols, color)
        symbols.columnHelperColorArray?.let { field ->
            putColorArray(field, color)
        }
        symbols.themeIdHelperColorId?.let { field ->
            runCatching {
                val ids = field.get(null) as? SparseArray<Any?> ?: return@runCatching
                ids.put(CUSTOM_THEME_ID1, CUSTOM_THEME_ID1)
                ids.put(CUSTOM_THEME_ID2, CUSTOM_THEME_ID2)
            }
        }
        log("CustomTheme web colors installed: ${formatColor(color)}")
    }

    private fun installThemeMaps(symbols: RestoredCustomThemeSymbols, primaryColor: Int) {
        runCatching {
            @Suppress("UNCHECKED_CAST")
            (symbols.themeName.get(null) as? MutableMap<String, Int>)?.apply {
                put("custom1", CUSTOM_THEME_ID1)
                put("custom2", CUSTOM_THEME_ID2)
            }
            putColorArray(symbols.themeHelperColorArray, primaryColor)
            buildTheme(primaryColor, symbols.themeColorsClass)?.let { theme ->
                @Suppress("UNCHECKED_CAST")
                (symbols.allThemes?.get(null) as? MutableMap<Long, Any>)?.apply {
                    put(CUSTOM_THEME_ID1.toLong(), theme)
                    put(CUSTOM_THEME_ID2.toLong(), theme)
                }
            }
        }.onFailure { log("CustomTheme map installation failed", it) }
    }

    private fun putColorArray(field: Field, primaryColor: Int) {
        val array = field.get(null) as? SparseArray<IntArray> ?: return
        val colors = generateColorArray(primaryColor)
        array.put(CUSTOM_THEME_ID1, colors)
        array.put(CUSTOM_THEME_ID2, colors)
    }

    private fun hookThemeList(symbols: RestoredCustomThemeSymbols) {
        env.hookBefore(symbols.skinListMethod) { param ->
            val listHolder = param.args.firstOrNull() ?: return@hookBefore
            val list = listHolder.javaClass.fieldOrNull("mList")?.let { field ->
                runCatching { field.get(listHolder) as? MutableList<Any> }.getOrNull()
            } ?: return@hookBefore
            if (list.any { it.readIntField("mId") == CUSTOM_THEME_ID1 || it.readIntField("mId") == CUSTOM_THEME_ID2 }) {
                return@hookBefore
            }
            val skin = symbols.skinClass.newInstanceOrNull() ?: return@hookBefore
            val id = if (ModuleSettings.getCustomThemeColor(prefs) == ModuleSettings.DEFAULT_CUSTOM_THEME_COLOR) {
                CUSTOM_THEME_ID1
            } else {
                CUSTOM_THEME_ID2
            }
            skin.setIntField("mId", id)
            skin.setObjectField("mName", "自定义颜色")
            skin.setBooleanField("mIsFree", true)
            list.add(minOf(3, list.size), skin)
        }
    }

    private fun hookThemeClick(symbols: RestoredCustomThemeSymbols) {
        val method = symbols.themeListClickClass.allMethods().firstOrNull {
            it.name == "onClick" && it.parameterTypes.contentEquals(arrayOf(View::class.java))
        } ?: return
        env.hookBefore(method) { param ->
            val view = param.args.firstOrNull() as? View ?: return@hookBefore
            val skin = view.tag ?: return@hookBefore
            val id = skin.readIntField("mId")
            if (id != CUSTOM_THEME_ID1 && id != CUSTOM_THEME_ID2) return@hookBefore

            val dialogInput = EditText(view.context).apply {
                setSingleLine(true)
                setSelectAllOnFocus(true)
                setText(formatColor(ModuleSettings.getCustomThemeColor(prefs)).removePrefix("#"))
                hint = "RRGGBB"
                inputType = android.text.InputType.TYPE_CLASS_TEXT
            }
            val dialog = AlertDialog.Builder(view.context)
                .setTitle("自定义颜色")
                .setMessage("输入 6 位十六进制主色，例如 FB7299")
                .setView(dialogInput)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val color = parseColor(dialogInput.text?.toString()) ?: run {
                        dialogInput.error = "请输入有效的 RRGGBB 色码"
                        return@setOnClickListener
                    }
                    prefs.edit().putInt(ModuleSettings.KEY_CUSTOM_THEME_COLOR, color).apply()
                    installThemeMaps(symbols, color)
                    skin.setIntField("mId", if (id == CUSTOM_THEME_ID1) CUSTOM_THEME_ID2 else CUSTOM_THEME_ID1)
                    dialog.dismiss()
                    param.invokeOriginalMethod()
                }
            }
            dialog.show()
            param.result = null
        }
    }

    private fun buildTheme(primaryColor: Int, themeClass: Class<*>?): Any? {
        themeClass ?: return null
        val constructor = themeClass.declaredConstructors.firstOrNull { Modifier.isPrivate(it.modifiers) }
            ?: themeClass.declaredConstructors.maxByOrNull { it.parameterTypes.size }
            ?: return null
        constructor.isAccessible = true
        if (constructor.parameterCount != 10) return null
        val garbClass = constructor.parameterTypes[0]
        val garb = garbClass.newInstanceOrNull() ?: garbClass.declaredConstructors
            .firstOrNull { it.parameterCount == 9 }
            ?.apply { isAccessible = true }
            ?.runCatching {
                newInstance(0L, true, true, "", 0L, 0L, 0L, 0L, 0L)
            }?.getOrNull()
            ?: return null
        val dayNight = constructor.parameterTypes[1].enumConstants?.firstOrNull() ?: return null
        val packed = primaryColor.toLong() and 0xFFFFFFFFL shl 32
        return runCatching {
            constructor.newInstance(
                garb,
                dayNight,
                packed,
                packed,
                Color.WHITE.toLong() shl 32,
                Color.WHITE.toLong() shl 32,
                Color.WHITE.toLong() shl 32,
                Color.WHITE.toLong() shl 32,
                Color.WHITE.toLong() shl 32,
                true,
            )
        }.getOrNull()
    }

    private fun Any.readIntField(name: String): Int? =
        javaClass.allFields().firstOrNull { it.name == name }?.let { field ->
            runCatching { (field.get(this) as? Number)?.toInt() }.getOrNull()
        }

    private fun parseColor(raw: String?): Int? {
        val value = raw?.trim()?.removePrefix("#") ?: return null
        if (!value.matches(Regex("[0-9a-fA-F]{6}"))) return null
        return runCatching { Color.parseColor("#$value") }.getOrNull()
    }

    private fun formatColor(color: Int): String = "#%06X".format(color and 0xFFFFFF)

    private companion object {
        private const val CUSTOM_THEME_ID1 = 114514
        private const val CUSTOM_THEME_ID2 = 1919810
        private const val MAIN_ACTIVITY = "tv.danmaku.bili.MainActivityV2"
        // 播放页进度条拖动图标模型(protobuf 生成类):newBuilder().setDragLeftPng(url)...build()
        private const val PLAYER_ICON_CLASS = "com.bapis.bilibili.app.view.v1.PlayerIcon"

        private fun generateColorArray(primaryColor: Int): IntArray {
            val colors = IntArray(4)
            val hsv = FloatArray(3)
            val result = FloatArray(3)
            Color.colorToHSV(primaryColor, hsv)
            colors[0] = primaryColor
            hsv.copyInto(result)
            result[2] -= result[2] * 0.2f
            colors[1] = Color.HSVToColor(result)
            hsv.copyInto(result)
            result[2] = (result[2] + result[2] * 0.1f).coerceAtMost(1f)
            colors[2] = Color.HSVToColor(result)
            colors[3] = -0x4c000000 or (colors[1] and 0xFFFFFF)
            return colors
        }
    }
}
