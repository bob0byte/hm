package com.ahmed.fpssetter

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/** A searchable list of apps with icons and tick boxes. */
object AppPicker {

    data class Entry(
        val pkg: String,
        val label: String,
        val isGame: Boolean,
        val isSystem: Boolean
    )

    /** Apps that have a home-screen icon. Slow-ish: call from a background thread. */
    fun load(ctx: Context, includeSystem: Boolean): List<Entry> {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != ctx.packageName }
            .map { info ->
                @Suppress("DEPRECATION")
                val isGame = info.category == ApplicationInfo.CATEGORY_GAME ||
                    (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                Entry(info.packageName, pm.getApplicationLabel(info).toString(), isGame, isSystem)
            }
            .filter { includeSystem || !it.isSystem }
    }

    fun show(
        activity: Activity,
        title: String,
        entries: List<Entry>,
        selected: Set<String>,
        gamesFirst: Boolean,
        onDone: (Set<String>) -> Unit
    ) {
        val picked = selected.toMutableSet()
        // Already-ticked apps on top, then games (if wanted), then A to Z
        val all = entries.sortedWith(
            compareByDescending<Entry> { it.pkg in selected }
                .thenByDescending { gamesFirst && it.isGame }
                .thenBy { it.label.lowercase(Locale.ROOT) }
        )
        var shown = all

        val inflater = activity.layoutInflater
        val view = inflater.inflate(R.layout.dialog_picker, null)
        val search = view.findViewById<EditText>(R.id.search)
        val list = view.findViewById<ListView>(R.id.list)
        val empty = view.findViewById<TextView>(R.id.empty)
        val pm = activity.packageManager
        val icons = HashMap<String, Drawable>()

        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int): Any = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: inflater.inflate(R.layout.row_app, parent, false)
                val e = shown[position]
                row.findViewById<TextView>(R.id.appName).text = e.label
                row.findViewById<TextView>(R.id.appInfo).text = when {
                    e.isGame -> "Game"
                    e.isSystem -> "Came with the phone"
                    else -> "Installed by you"
                }
                val icon = icons[e.pkg] ?: try {
                    pm.getApplicationIcon(e.pkg).also { icons[e.pkg] = it }
                } catch (t: Throwable) {
                    null
                }
                row.findViewById<ImageView>(R.id.appIcon).setImageDrawable(icon)
                row.findViewById<CheckBox>(R.id.appCheck).isChecked = e.pkg in picked
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val pkg = shown[position].pkg
            if (!picked.add(pkg)) picked.remove(pkg)
            adapter.notifyDataSetChanged()
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                shown = if (q.isEmpty()) all else all.filter {
                    it.label.lowercase(Locale.ROOT).contains(q) || it.pkg.contains(q)
                }
                empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
                adapter.notifyDataSetChanged()
            }
        })

        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(view)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Done") { _, _ -> onDone(picked.toSet()) }
            .show()
    }
}
