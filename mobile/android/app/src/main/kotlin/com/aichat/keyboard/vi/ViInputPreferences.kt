/*
 * Copyright (C) 2026 AIChat
 */

package com.aichat.keyboard.vi

import androidx.compose.runtime.Composable
import com.aichat.vi.InputMethod
import com.aichat.vi.ToneStyle
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisPreferenceModel
import dev.patrickgold.jetpref.datastore.ui.ListPreference
import dev.patrickgold.jetpref.datastore.ui.PreferenceGroup
import dev.patrickgold.jetpref.datastore.ui.PreferenceUiScope
import dev.patrickgold.jetpref.datastore.ui.SwitchPreference
import dev.patrickgold.jetpref.datastore.ui.listPrefEntries
import org.florisboard.lib.compose.stringRes

/**
 * "Tiếng Việt" group of the keyboard settings screen. The engine reads these preferences on every
 * key ([ViInputBridge]), so a change applies to the next word without restarting the keyboard.
 */
@Composable
fun PreferenceUiScope<FlorisPreferenceModel>.ViInputPreferenceGroup() {
    PreferenceGroup(title = stringRes(R.string.aichat_vi__group_title)) {
        ListPreference(
            prefs.aichatVietnamese.inputMethod,
            title = stringRes(R.string.aichat_vi__input_method__label),
            entries = listPrefEntries {
                entry(
                    key = InputMethod.TELEX,
                    label = stringRes(R.string.aichat_vi__input_method__telex),
                    description = stringRes(R.string.aichat_vi__input_method__telex_summary),
                )
                entry(
                    key = InputMethod.VNI,
                    label = stringRes(R.string.aichat_vi__input_method__vni),
                    description = stringRes(R.string.aichat_vi__input_method__vni_summary),
                )
            },
        )
        ListPreference(
            prefs.aichatVietnamese.toneStyle,
            title = stringRes(R.string.aichat_vi__tone_style__label),
            entries = listPrefEntries {
                entry(key = ToneStyle.MODERN, label = stringRes(R.string.aichat_vi__tone_style__modern))
                entry(key = ToneStyle.CLASSIC, label = stringRes(R.string.aichat_vi__tone_style__classic))
            },
        )
        SwitchPreference(
            prefs.aichatVietnamese.autoRestoreEnglish,
            title = stringRes(R.string.aichat_vi__auto_restore_english__label),
            summary = stringRes(R.string.aichat_vi__auto_restore_english__summary),
        )
    }
}
