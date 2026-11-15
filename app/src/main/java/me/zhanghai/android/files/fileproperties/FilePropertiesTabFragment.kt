/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.fileproperties

import android.location.Geocoder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.core.view.forEach
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.FilePropertiesAddressItemBinding
import me.zhanghai.android.files.databinding.FilePropertiesTabFragmentBinding
import me.zhanghai.android.files.databinding.FilePropertiesTabItemBinding
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.awaitGetFromLocation
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.isGeocoderPresent
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.userFriendlyString

abstract class FilePropertiesTabFragment : Fragment() {
    protected lateinit var binding: FilePropertiesTabFragmentBinding

    protected var addressJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        FilePropertiesTabFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        binding.swipeRefreshLayout.setOnRefreshListener { refresh() }
    }

    abstract fun refresh()

    // The address lookup sends the coordinates to the system geocoder (usually Google), so only a tap starts it.
    protected fun ViewBuilder.addAddressItem(latitude: Double, longitude: Double) {
        val itemBinding = FilePropertiesAddressItemBinding.inflate(
            linearLayout.context.layoutInflater, linearLayout, false
        )
        itemBinding.root.tag = itemBinding
        addView(itemBinding)
        itemBinding.button.setOnClickListener {
            itemBinding.button.isEnabled = false
            itemBinding.button.setText(R.string.file_properties_media_address_loading)
            val geocoder = if (isGeocoderPresent) Geocoder(requireContext()) else null
            addressJob = viewLifecycleOwner.lifecycleScope.launch {
                val address = try {
                    geocoder?.awaitGetFromLocation(latitude, longitude, 1)?.firstOrNull()
                        ?.userFriendlyString
                } catch (e: Exception) {
                    null
                }
                if (isActive) {
                    if (address != null) {
                        itemBinding.button.visibility = View.GONE
                        itemBinding.hint.visibility = View.GONE
                        itemBinding.result.text = address
                    } else {
                        itemBinding.button.isEnabled = geocoder != null
                        itemBinding.button.setText(R.string.file_properties_media_address_show)
                        itemBinding.result.setText(
                            if (geocoder != null) {
                                R.string.file_properties_media_address_failed
                            } else {
                                R.string.file_properties_media_address_unavailable
                            }
                        )
                    }
                    itemBinding.result.visibility = View.VISIBLE
                }
            }
        }
    }

    protected inline fun <T> bindView(stateful: Stateful<T>, block: ViewBuilder.(T) -> Unit) {
        val value = stateful.value
        val hasValue = value != null
        binding.progress.fadeToVisibilityUnsafe(stateful is Loading && !hasValue)
        binding.swipeRefreshLayout.isRefreshing = stateful is Loading && hasValue
        binding.errorText.fadeToVisibilityUnsafe(stateful is Failure && !hasValue)
        if (stateful is Failure) {
            stateful.throwable.printStackTrace()
            val error = stateful.throwable.toString()
            if (hasValue) {
                showToast(error)
            } else {
                binding.errorText.text = error
            }
        }
        binding.scrollView.fadeToVisibilityUnsafe(hasValue)
        if (value != null) {
            ViewBuilder(binding.linearLayout).apply {
                block(value)
                build()
            }
        }
    }

    protected class ViewBuilder(val linearLayout: LinearLayout) {
        private val scrapViews = mutableMapOf<Class<out ViewBinding>, MutableList<ViewBinding>>()

        init {
            linearLayout.forEach { view ->
                val binding = view.tag as ViewBinding
                scrapViews.getOrPut(binding.javaClass) { mutableListOf() } += binding
            }
            linearLayout.removeAllViews()
        }

        @Suppress("UNCHECKED_CAST")
        fun <T : ViewBinding> getScrapItemBinding(bindingClass: Class<T>): T? =
            scrapViews[bindingClass]?.removeLastOrNull() as T?

        fun addView(binding: ViewBinding) {
            linearLayout.addView(binding.root)
        }

        fun addItemView(
            hint: String,
            text: String,
            onClickListener: ((View) -> Unit)? = null
        ): TextView {
            val itemBinding =
                getScrapItemBinding(FilePropertiesTabItemBinding::class.java)?.also { addView(it) }
                    ?: FilePropertiesTabItemBinding.inflate(
                        linearLayout.context.layoutInflater, linearLayout, true
                    )
                        .also { it.root.tag = it }
            itemBinding.textInputLayout.hint = hint
            itemBinding.textInputLayout.setDropDown(onClickListener != null)
            itemBinding.text.setText(text)
            itemBinding.text.setTextIsSelectable(onClickListener == null)
            itemBinding.text.setOnClickListener(onClickListener?.let { View.OnClickListener(it) })
            return itemBinding.text
        }

        fun addItemView(
            @StringRes hintRes: Int,
            text: String,
            onClickListener: ((View) -> Unit)? = null
        ): TextView = addItemView(linearLayout.context.getString(hintRes), text, onClickListener)

        fun build() {
            scrapViews.clear()
        }
    }
}
