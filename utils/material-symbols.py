#!/usr/bin/env python3
# Copyright (c) 2026 Ordo contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Writes the pictograms from Material Symbols Rounded (filled) into the drawables.

Material Symbols are licensed under Apache-2.0: https://github.com/google/material-design-icons
Only the viewport and the path data of each drawable are replaced, so tint and mirroring stay.
Run from the repository root: python3 utils/material-symbols.py
"""
import re
import sys
import urllib.request
from pathlib import Path

COMMIT = "10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7"
URL = ("https://raw.githubusercontent.com/google/material-design-icons/" + COMMIT +
       "/symbols/android/{0}/materialsymbolsrounded/{0}_fill1_24px.xml")
RES = Path(__file__).resolve().parent.parent / "app/src/main/res"

# Drawable (without .xml, in drawable/ unless a folder is given) -> Material Symbol
ICONS = {
    "about_icon_white_24dp": "help",
    "add_icon_white_24dp": "add",
    "ai_check_icon_white_24dp": "check_circle",
    "ai_cross_icon_white_24dp": "cancel",
    "ai_hint_icon_white_24dp": "lightbulb",
    "ai_memory_icon_white_24dp": "memory",
    "ai_phone_icon_white_24dp": "smartphone",
    "ai_privacy_icon_white_24dp": "shield_lock",
    "ai_storage_icon_white_24dp": "hard_drive",
    "palette_icon_white_24dp": "palette",
    "assistant_icon_white_24dp": "wand_stars",
    "send_icon_white_24dp": "arrow_upward",
    "alarm_icon_white_24dp": "alarm",
    "android_icon_white_24dp": "android",
    "apps_icon_white_24dp": "apps",
    "archive_icon_white_24dp": "folder_zip",
    "arrow_end_icon_white_24dp": "chevron_right",
    "audio_icon_white_24dp": "music_note",
    "audio_output_icon_24dp": "cast",
    "ic_launcher_music_foreground": "music_note",
    "settings_pictogram_builtin_apps": "widgets",
    "audio_equalizer_icon_24dp": "equalizer",
    "audio_tune_icon_24dp": "tune",
    "audio_speaker_icon_24dp": "speaker",
    "audio_surround_icon_24dp": "surround_sound",
    "audio_graphic_eq_icon_24dp": "graphic_eq",
    "audio_hearing_icon_24dp": "hearing",
    "audio_fast_forward_icon_24dp": "fast_forward",
    "audio_headphones_icon_24dp": "headphones",
    "audio_timer_icon_24dp": "timer",
    "audio_speed_icon_24dp": "speed",
    "audio_balance_icon_24dp": "balance",
    "audio_add_icon_24dp": "add",
    "audio_remove_icon_24dp": "remove",
    "audio_spatial_icon_24dp": "spatial_audio",
    "audio_width_icon_24dp": "width",
    "audio_warmth_icon_24dp": "local_fire_department",
    "audio_clarity_icon_24dp": "flare",
    "audio_volume_icon_24dp": "volume_up",
    "audio_playback_icon_24dp": "play_circle",
    "backup_icon_white_24dp": "backup",
    "camera_icon_white_24dp": "photo_camera",
    "check_icon_control_normal_24dp": "check",
    "close_icon_control_normal_24dp": "close",
    "close_icon_white_24dp": "close",
    "cloud_icon_white_24dp": "cloud",
    "computer_icon_white_24dp": "computer",
    "copy_icon_control_normal_24dp": "content_copy",
    "cut_icon_control_normal_24dp": "content_cut",
    "delete_icon_control_normal_24dp": "delete",
    "delete_icon_white_24dp": "delete",
    "device_icon_white_24dp": "smartphone",
    "drawable-desk/device_icon_white_24dp": "desktop_windows",
    "drawable-sw600dp/device_icon_white_24dp": "tablet_android",
    "drawable-television/device_icon_white_24dp": "tv",
    "directory_icon_control_normal_24dp": "folder",
    "directory_icon_white_24dp": "folder",
    "document_icon_white_24dp": "description",
    "download_icon_white_24dp": "download",
    "drag_handle_icon_white_24dp": "drag_handle",
    "edit_icon": "edit",
    "error_icon_white_24dp": "error",
    "extract_icon_control_normal_24dp": "unarchive",
    "file_apk_icon": "android",
    "file_archive_icon": "folder_zip",
    "file_audio_icon": "music_note",
    "file_calendar_icon": "event",
    "file_certificate_icon": "fingerprint",
    "file_code_icon": "code",
    "file_contact_icon": "person",
    "file_directory_icon": "folder",
    "file_document_icon": "article",
    "file_ebook_icon": "book",
    "file_email_icon": "mail",
    "file_font_icon": "font_download",
    "file_generic_icon": "draft",
    "file_icon_white_24dp": "draft",
    "file_image_icon": "image",
    "file_presentation_icon": "slideshow",
    "file_spreadsheet_icon": "table_chart",
    "file_text_icon": "description",
    "file_video_icon": "movie",
    "filter_icon_control_normal_24dp": "filter_list",
    "image_icon_white_24dp": "image",
    "information_icon_white_24dp": "info",
    "legal_icon_white_24dp": "balance",
    "lock_icon_white_24dp": "lock",
    "lock_open_icon_white_24dp": "lock_open",
    "menu_icon_control_normal_24dp": "menu",
    "more_vertical_icon_white_24dp": "more_vert",
    "move_icon_control_normal_24dp": "drive_file_move",
    "notification_icon": "folder",
    "notification_icon_white_24dp": "notifications",
    "open_as_icon": "open_in_new",
    "paste_icon_control_normal_24dp": "content_paste",
    "people_icon_control_normal_24dp": "group",
    "person_icon_control_normal_24dp": "person",
    "person_icon_white_24dp": "person",
    "podcast_icon_white_24dp": "podcasts",
    "reset_icon_white_24dp": "replay",
    "restore_icon_white_24dp": "restore_from_trash",
    "ringtone_icon_white_24dp": "phonelink_ring",
    "save_icon_control_normal_24dp": "save",
    "sd_card_icon_white_24dp": "sd_card",
    "search_icon_control_normal_24dp": "search",
    "settings_icon_white_24dp": "settings",
    "share_icon_control_normal_24dp": "share",
    "shared_directory_icon_white_24dp": "folder_shared",
    "sort_icon_control_normal_24dp": "sort",
    "stop_icon_white_24dp": "stop",
    "storage_analysis_icon_white_24dp": "pie_chart",
    "text_file_icon_white_24dp": "description",
    "usb_icon_white_24dp": "usb",
    "video_icon_white_24dp": "movie",
    "settings_wallpaper_icon_white_24dp": "wallpaper",
    "settings_header_position_icon_white_24dp": "search",
    "settings_motion_icon_white_24dp": "animation",
    "settings_list_motion_icon_white_24dp": "format_list_bulleted",
    "settings_language_icon_white_24dp": "translate",
    "settings_preview_icon_white_24dp": "science",
    "settings_speed_icon_white_24dp": "bolt",
    "settings_home_icon_white_24dp": "home",
    "settings_ellipsize_icon_white_24dp": "short_text",
    "settings_root_icon_white_24dp": "admin_panel_settings",
    "settings_pdf_icon_white_24dp": "picture_as_pdf",
    "settings_storage_icon_white_24dp": "storage",
    "settings_bookmark_icon_white_24dp": "bookmark",
    "settings_auto_delete_icon_white_24dp": "auto_delete",
    "settings_link_icon_white_24dp": "link",
    "settings_tile_icon_white_24dp": "dashboard_customize",
    "settings_anonymous_icon_white_24dp": "person_off",
    "settings_key_icon_white_24dp": "key",
    "settings_port_icon_white_24dp": "pin",
    "settings_writable_icon_white_24dp": "edit",
    "play_icon_24dp": "play_arrow",
    "pause_icon_24dp": "pause",
    "repeat_icon_24dp": "repeat",
    "repeat_one_icon_24dp": "repeat_one",
    "shuffle_icon_24dp": "shuffle",
    "skip_next_icon_24dp": "skip_next",
    "skip_previous_icon_24dp": "skip_previous",
    "equalizer_icon_24dp": "equalizer",
    "expand_less_icon_24dp": "expand_less",
    "expand_more_icon_24dp": "expand_more",
}


def replace_once(pattern, replacement, text, name):
    text, count = re.subn(pattern, replacement, text)
    if count != 1:
        sys.exit(f"{name}: expected one match for {pattern}, found {count}")
    return text


def main():
    cache = {}
    for drawable, symbol in ICONS.items():
        if symbol not in cache:
            with urllib.request.urlopen(URL.format(symbol)) as response:
                source = response.read().decode()
            paths = re.findall(r'android:pathData="([^"]+)"', source)
            if len(paths) != 1 or 'android:viewportWidth="960"' not in source:
                sys.exit(f"{symbol}: unexpected source format")
            cache[symbol] = paths[0]
        file = RES / (drawable if "/" in drawable else "drawable/" + drawable)
        file = file.with_name(file.name + ".xml")
        text = file.read_text()
        text = replace_once(r'android:viewportWidth="[\d.]+"', 'android:viewportWidth="960"', text, drawable)
        text = replace_once(r'android:viewportHeight="[\d.]+"', 'android:viewportHeight="960"', text, drawable)
        text = replace_once(r'android:pathData="[^"]+"', f'android:pathData="{cache[symbol]}"', text, drawable)
        # Material Symbols are drawn for the default fill rule.
        text = re.sub(r'\s*android:fillType="evenOdd"', "", text)
        file.write_text(text)
    print(f"{len(ICONS)} drawables written from {len(cache)} symbols")


if __name__ == "__main__":
    main()
