/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.settings

import android.os.Environment
import android.text.TextUtils
import java8.nio.file.Path
import java8.nio.file.Paths
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.compat.EnvironmentCompat2
import me.zhanghai.android.files.filelist.FileSortOptions
import me.zhanghai.android.files.filelist.FileViewType
import me.zhanghai.android.files.filelist.OpenApkDefaultAction
import me.zhanghai.android.files.navigation.BookmarkDirectory
import me.zhanghai.android.files.navigation.StandardDirectorySettings
import me.zhanghai.android.files.provider.root.RootStrategy
import me.zhanghai.android.files.storage.FileSystemRoot
import me.zhanghai.android.files.storage.PrimaryStorageVolume
import me.zhanghai.android.files.storage.Storage
import me.zhanghai.android.files.theme.custom.ThemeColor
import me.zhanghai.android.files.theme.night.NightMode
import java.io.File

object Settings {
    val STORAGES: SettingLiveData<List<Storage>> =
        ParcelValueSettingLiveData(
            R.string.pref_key_storages,
            listOf(FileSystemRoot(null, true), PrimaryStorageVolume(null, true))
        )

    val FILE_LIST_DEFAULT_DIRECTORY: SettingLiveData<Path> =
        ParcelValueSettingLiveData(
            R.string.pref_key_file_list_default_directory,
            @Suppress("DEPRECATION")
            Paths.get(Environment.getExternalStorageDirectory().absolutePath)
        )

    val SHOW_HOME_ON_START: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_show_home_on_start, R.bool.pref_default_value_show_home_on_start
        )

    val FILE_LIST_PERSISTENT_DRAWER_OPEN: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_file_list_persistent_drawer_open,
            R.bool.pref_default_value_file_list_persistent_drawer_open
        )

    val FILE_LIST_SHOW_HIDDEN_FILES: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_file_list_show_hidden_files,
            R.bool.pref_default_value_file_list_show_hidden_files
        )

    val HOME_RECENT_VIEW: SettingLiveData<String> =
        StringSettingLiveData(
            R.string.pref_key_home_recent_view, R.string.pref_default_value_home_recent_view
        )

    val HOME_SHOW_QUICK_ACCESS: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_home_show_quick_access,
            R.bool.pref_default_value_home_show_quick_access
        )

    // Folders are backed up unless listed here, so new folders are included by themselves.
    val BACKUP_EXCLUDED: SettingLiveData<Set<String>> =
        StringSetSettingLiveData(
            R.string.pref_key_backup_excluded, R.array.pref_default_value_empty_set
        )

    val BACKUP_EXTRA_FOLDERS: SettingLiveData<Set<String>> =
        StringSetSettingLiveData(
            R.string.pref_key_backup_extra_folders, R.array.pref_default_value_empty_set
        )

    val FILE_LIST_SEARCH_CONTENT: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_file_list_search_content,
            R.bool.pref_default_value_file_list_search_content
        )

    val FILE_LIST_SEARCH_REGEX: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_file_list_search_regex, R.bool.pref_default_value_file_list_search_regex
        )

    val FILE_LIST_VIEW_TYPE: SettingLiveData<FileViewType> =
        EnumSettingLiveData(
            R.string.pref_key_file_list_view_type, R.string.pref_default_value_file_list_view_type,
            FileViewType::class.java
        )

    val FILE_LIST_SORT_OPTIONS: SettingLiveData<FileSortOptions> =
        ParcelValueSettingLiveData(
            R.string.pref_key_file_list_sort_options,
            FileSortOptions(FileSortOptions.By.NAME, FileSortOptions.Order.ASCENDING, true)
        )

    val TRASH_ENABLED: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_trash_enabled, R.bool.pref_default_value_trash_enabled
        )

    val TRASH_RETENTION_DAYS: SettingLiveData<String> =
        StringSettingLiveData(
            R.string.pref_key_trash_retention_days,
            R.string.pref_default_value_trash_retention_days
        )

    val CREATE_ARCHIVE_TYPE: SettingLiveData<Int> =
        ResourceIdSettingLiveData(R.string.pref_key_create_archive_type, R.id.zipRadio)

    val FTP_SERVER_ANONYMOUS_LOGIN: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_ftp_server_anonymous_login,
            R.bool.pref_default_value_ftp_server_anonymous_login
        )

    val FTP_SERVER_USERNAME: SettingLiveData<String> =
        StringSettingLiveData(
            R.string.pref_key_ftp_server_username, R.string.pref_default_value_ftp_server_username
        )

    val FTP_SERVER_PASSWORD: SettingLiveData<String> =
        StringSettingLiveData(
            R.string.pref_key_ftp_server_password, R.string.pref_default_value_empty
        )

    val FTP_SERVER_PORT: SettingLiveData<Int> =
        IntegerSettingLiveData(
            R.string.pref_key_ftp_server_port, R.integer.pref_default_value_ftp_server_port
        )

    val FTP_SERVER_HOME_DIRECTORY: SettingLiveData<Path> =
        ParcelValueSettingLiveData(
            R.string.pref_key_ftp_server_home_directory,
            @Suppress("DEPRECATION")
            Paths.get(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    .absolutePath
            )
        )

    val FTP_SERVER_WRITABLE: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_ftp_server_writable, R.bool.pref_default_value_ftp_server_writable
        )

    val THEME_COLOR: SettingLiveData<ThemeColor> =
        EnumSettingLiveData(
            R.string.pref_key_theme_color, R.string.pref_default_value_theme_color,
            ThemeColor::class.java
        )

    val MATERIAL_DESIGN_3: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_material_design_3, R.bool.pref_default_value_material_design_3
        )

    val NIGHT_MODE: SettingLiveData<NightMode> =
        EnumSettingLiveData(
            R.string.pref_key_night_mode, R.string.pref_default_value_night_mode,
            NightMode::class.java
        )

    /** Off: Ordo's own colours; on: the wallpaper's colours (Material You, Android 12+). */
    val WALLPAPER_COLORS: SettingLiveData<Boolean> =
        BooleanSettingLiveData(R.string.pref_key_wallpaper_colors, R.bool.pref_default_value_wallpaper_colors)

    /** aurora, blue, mint, violet or amber: see accent_overlays.xml. */
    val ACCENT: SettingLiveData<String> =
        StringSettingLiveData(R.string.pref_key_accent, R.string.pref_default_value_accent)

    /** Ordo Musik's and Ordo Video's own accent: "ordo" (same as Ordo, the default) or one of ACCENT's values. */
    val MUSIC_ACCENT: SettingLiveData<String> =
        StringSettingLiveData(R.string.pref_key_music_accent, R.string.pref_default_value_app_accent)

    val VIDEO_ACCENT: SettingLiveData<String> =
        StringSettingLiveData(R.string.pref_key_video_accent, R.string.pref_default_value_app_accent)

    /** Ordo Video's settings page (settings_video.xml); what the values mean: viewer/video/VideoPrefs.kt. */
    val VIDEO_END: SettingLiveData<String> =
        StringSettingLiveData(R.string.pref_key_video_end, R.string.pref_default_value_video_end)
    val VIDEO_RESUME: SettingLiveData<String> =
        StringSettingLiveData(R.string.pref_key_video_resume, R.string.pref_default_value_video_resume)
    val VIDEO_SPEED: SettingLiveData<String> =
        StringSettingLiveData(R.string.pref_key_video_speed, R.string.pref_default_value_video_speed)
    val VIDEO_JUMP: SettingLiveData<String> =
        StringSettingLiveData(R.string.pref_key_video_jump, R.string.pref_default_value_video_jump)
    val VIDEO_RESIZE: SettingLiveData<String> =
        StringSettingLiveData(R.string.pref_key_video_resize, R.string.pref_default_value_video_resize)
    val VIDEO_ORIENTATION: SettingLiveData<String> =
        StringSettingLiveData(R.string.pref_key_video_orientation, R.string.pref_default_value_video_orientation)
    /** Ordo Video's picture (viewer/video/picture/PicturePolicy.kt): frame rate matching and enhancement. */
    val VIDEO_FRAME_RATE: SettingLiveData<String> =
        StringSettingLiveData(R.string.pref_key_video_frame_rate, R.string.pref_default_value_video_frame_rate)
    val VIDEO_PICTURE: SettingLiveData<String> =
        StringSettingLiveData(R.string.pref_key_video_picture, R.string.pref_default_value_video_picture)
    /** "HDR-Darstellung": automatic or off (viewer/video/picture/HdrToneMap.kt). */
    val VIDEO_HDR: SettingLiveData<String> =
        StringSettingLiveData(R.string.pref_key_video_hdr, R.string.pref_default_value_video_hdr)
    /** "Upscaling": Lanczos or Detail plus (viewer/video/picture/DetailPlus.kt). */
    val VIDEO_UPSCALER: SettingLiveData<String> =
        StringSettingLiveData(R.string.pref_key_video_upscaler, R.string.pref_default_value_video_upscaler)
    val VIDEO_AMBIENT_GAMMA: SettingLiveData<Boolean> =
        BooleanSettingLiveData(R.string.pref_key_video_ambient_gamma, R.bool.pref_default_value_false)
    val VIDEO_KEEP_SCREEN_ON: SettingLiveData<Boolean> =
        BooleanSettingLiveData(R.string.pref_key_video_keep_screen_on, R.bool.pref_default_value_true)
    val VIDEO_GESTURE_BRIGHTNESS: SettingLiveData<Boolean> =
        BooleanSettingLiveData(R.string.pref_key_video_gesture_brightness, R.bool.pref_default_value_true)
    val VIDEO_GESTURE_VOLUME: SettingLiveData<Boolean> =
        BooleanSettingLiveData(R.string.pref_key_video_gesture_volume, R.bool.pref_default_value_true)
    val VIDEO_GESTURE_SEEK: SettingLiveData<Boolean> =
        BooleanSettingLiveData(R.string.pref_key_video_gesture_seek, R.bool.pref_default_value_true)
    val VIDEO_GESTURE_DOUBLE_TAP: SettingLiveData<Boolean> =
        BooleanSettingLiveData(R.string.pref_key_video_gesture_double_tap, R.bool.pref_default_value_true)
    val VIDEO_GESTURE_HOLD: SettingLiveData<Boolean> =
        BooleanSettingLiveData(R.string.pref_key_video_gesture_hold, R.bool.pref_default_value_true)
    val VIDEO_HOLD_SPEED: SettingLiveData<String> =
        StringSettingLiveData(R.string.pref_key_video_hold_speed, R.string.pref_default_value_video_hold_speed)
    val VIDEO_REMEMBER_BRIGHTNESS: SettingLiveData<Boolean> =
        BooleanSettingLiveData(R.string.pref_key_video_remember_brightness, R.bool.pref_default_value_false)
    val VIDEO_AUDIO_LANGUAGE: SettingLiveData<String> =
        StringSettingLiveData(R.string.pref_key_video_audio_language, R.string.pref_default_value_video_audio_language)
    val VIDEO_SORT: SettingLiveData<String> =
        StringSettingLiveData(R.string.pref_key_video_sort, R.string.pref_default_value_video_sort)
    val VIDEO_VIEW: SettingLiveData<String> =
        StringSettingLiveData(R.string.pref_key_video_view, R.string.pref_default_value_video_view)
    val VIDEO_SHOW_CONTINUE: SettingLiveData<Boolean> =
        BooleanSettingLiveData(R.string.pref_key_video_show_continue, R.bool.pref_default_value_true)
    val VIDEO_CAMERA_NAMES: SettingLiveData<Boolean> =
        BooleanSettingLiveData(R.string.pref_key_video_camera_names, R.bool.pref_default_value_true)
    val VIDEO_HIDDEN_FOLDERS: SettingLiveData<Set<String>> =
        StringSetSettingLiveData(
            R.string.pref_key_video_hidden_folders, R.array.pref_default_value_video_hidden_folders
        )
    val VIDEO_NO_HISTORY: SettingLiveData<Boolean> =
        BooleanSettingLiveData(R.string.pref_key_video_no_history, R.bool.pref_default_value_false)
    val VIDEO_SUBTITLE_LANGUAGE: SettingLiveData<String> = StringSettingLiveData(
        R.string.pref_key_video_subtitle_language, R.string.pref_default_value_video_subtitle_language
    )
    val VIDEO_SUBTITLES_AUTO: SettingLiveData<Boolean> =
        BooleanSettingLiveData(R.string.pref_key_video_subtitles_auto, R.bool.pref_default_value_true)
    val VIDEO_SUBTITLE_READING: SettingLiveData<String> = StringSettingLiveData(
        R.string.pref_key_video_subtitle_reading, R.string.pref_default_value_video_subtitle_reading
    )
    val VIDEO_SUBTITLE_SLOW_DOWN: SettingLiveData<Boolean> =
        BooleanSettingLiveData(R.string.pref_key_video_subtitle_slow_down, R.bool.pref_default_value_false)
    val VIDEO_SUBTITLE_SOUNDS: SettingLiveData<Boolean> =
        BooleanSettingLiveData(R.string.pref_key_video_subtitle_sounds, R.bool.pref_default_value_true)
    val VIDEO_SUBTITLE_SPEAKER_COLORS: SettingLiveData<Boolean> =
        BooleanSettingLiveData(R.string.pref_key_video_subtitle_speaker_colors, R.bool.pref_default_value_true)

    /** Ordo Musik: the player's colours from the playing cover (default) or the chosen accent everywhere. */
    val MUSIC_COVER_COLORS: SettingLiveData<Boolean> =
        BooleanSettingLiveData(R.string.pref_key_music_cover_colors, R.bool.pref_default_value_music_cover_colors)

    /** Ordo Musik's visual effects: maximal, balanced or saver (viewer/audio/MusicEffects.kt). */
    val MUSIC_EFFECTS: SettingLiveData<String> =
        StringSettingLiveData(R.string.pref_key_music_effects, R.string.pref_default_value_music_effects)

    /** full, subtle or off: see ui/Motion.kt. */
    val MOTION: SettingLiveData<String> =
        StringSettingLiveData(R.string.pref_key_motion, R.string.pref_default_value_motion)

    /** JSON-encoded live audio settings; see player/AudioSettings.kt for validation and migration defaults. */
    val AUDIO_PLAYER_SOUND_SETTINGS: SettingLiveData<String> =
        StringSettingLiveData(
            R.string.pref_key_audio_player_sound_settings,
            R.string.pref_default_value_audio_player_sound_settings
        )

    /** top or bottom: where the home screen's search bar sits. */
    val HOME_HEADER_POSITION: SettingLiveData<String> =
        StringSettingLiveData(
            R.string.pref_key_home_header_position, R.string.pref_default_value_home_header_position
        )

    /** turbo, balanced or eco: threads and how long the AI model stays loaded. */
    /** The new command understanding (CommandDesign.Guarded) as an opt-in preview; off by default. */
    val AI_PREVIEW: SettingLiveData<Boolean> =
        BooleanSettingLiveData(R.string.pref_key_ai_preview, R.bool.pref_default_value_ai_preview)

    val AI_SPEED: SettingLiveData<String> =
        StringSettingLiveData(R.string.pref_key_ai_speed, R.string.pref_default_value_ai_speed)

    val BLACK_NIGHT_MODE: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_black_night_mode, R.bool.pref_default_value_black_night_mode
        )

    val FILE_LIST_ANIMATION: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_file_list_animation, R.bool.pref_default_value_file_list_animation
        )

    val FILE_NAME_ELLIPSIZE: SettingLiveData<TextUtils.TruncateAt> =
        EnumSettingLiveData(
            R.string.pref_key_file_name_ellipsize, R.string.pref_default_value_file_name_ellipsize,
            TextUtils.TruncateAt::class.java
        )

    val STANDARD_DIRECTORY_SETTINGS: SettingLiveData<List<StandardDirectorySettings>> =
        ParcelValueSettingLiveData(R.string.pref_key_standard_directory_settings, emptyList())

    val BOOKMARK_DIRECTORIES: SettingLiveData<List<BookmarkDirectory>> =
        ParcelValueSettingLiveData(
            R.string.pref_key_bookmark_directories, listOf(
                BookmarkDirectory(
                    application.getString(R.string.settings_bookmark_directory_screenshots),
                    Paths.get(
                        File(
                            @Suppress("DEPRECATION")
                            Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_PICTURES
                            ), EnvironmentCompat2.DIRECTORY_SCREENSHOTS
                        ).absolutePath
                    )
                )
            )
        )

    val ROOT_STRATEGY: SettingLiveData<RootStrategy> =
        EnumSettingLiveData(
            R.string.pref_key_root_strategy, R.string.pref_default_value_root_strategy,
            RootStrategy::class.java
        )

    val ARCHIVE_FILE_NAME_ENCODING: SettingLiveData<String> =
        StringSettingLiveData(
            R.string.pref_key_archive_file_name_encoding,
            R.string.pref_default_value_archive_file_name_encoding
        )

    val OPEN_APK_DEFAULT_ACTION: SettingLiveData<OpenApkDefaultAction> =
        EnumSettingLiveData(
            R.string.pref_key_open_apk_default_action,
            R.string.pref_default_value_open_apk_default_action,
            OpenApkDefaultAction::class.java
        )

    val SHOW_PDF_THUMBNAIL_PRE_28: SettingLiveData<Boolean> = BooleanSettingLiveData(
        R.string.pref_key_show_pdf_thumbnail_pre_28,
        R.bool.pref_default_value_show_pdf_thumbnail_pre_28
    )

    val READ_REMOTE_FILES_FOR_THUMBNAIL: SettingLiveData<Boolean> =
        BooleanSettingLiveData(
            R.string.pref_key_read_remote_files_for_thumbnail,
            R.bool.pref_default_value_read_remote_files_for_thumbnail
        )
}
