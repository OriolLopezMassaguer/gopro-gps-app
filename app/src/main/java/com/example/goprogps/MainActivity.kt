package com.example.goprogps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.goprogps.databinding.ActivityMainBinding
import com.example.goprogps.model.VideoItem
import com.example.goprogps.ui.VideoAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_VIDEO
    else
        Manifest.permission.READ_EXTERNAL_STORAGE

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadVideos() else showPermissionRationale()
    }

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { openTrackDetail(it, "Selected Video") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.btnScan.setOnClickListener { checkPermissionAndLoad() }
        binding.btnPickFile.setOnClickListener { pickVideoLauncher.launch("video/*") }

        checkPermissionAndLoad()
    }

    private fun checkPermissionAndLoad() {
        when {
            ContextCompat.checkSelfPermission(this, requiredPermission) ==
                    PackageManager.PERMISSION_GRANTED -> loadVideos()
            shouldShowRequestPermissionRationale(requiredPermission) -> showPermissionRationale()
            else -> permissionLauncher.launch(requiredPermission)
        }
    }

    private fun loadVideos() {
        val videos = queryGoProVideos()
        binding.tvEmpty.visibility = if (videos.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.adapter = VideoAdapter(videos) { item ->
            openTrackDetail(item.uri, item.name)
        }
    }

    private fun queryGoProVideos(): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_TAKEN
        )
        val selection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? " +
                "OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("GH%.MP4", "GX%.MP4")
        val sort = "${MediaStore.Video.Media.DATE_TAKEN} DESC"

        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, selection, args, sort
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                result.add(VideoItem(
                    id = id,
                    name = cursor.getString(nameCol),
                    uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString()),
                    sizeBytes = cursor.getLong(sizeCol),
                    durationMs = cursor.getLong(durCol),
                    dateTaken = cursor.getLong(dateCol)
                ))
            }
        }
        return result
    }

    private fun openTrackDetail(uri: Uri, name: String) {
        val intent = Intent(this, TrackDetailActivity::class.java).apply {
            putExtra(TrackDetailActivity.EXTRA_URI, uri.toString())
            putExtra(TrackDetailActivity.EXTRA_NAME, name)
        }
        startActivity(intent)
    }

    private fun showPermissionRationale() {
        AlertDialog.Builder(this)
            .setTitle("Storage permission needed")
            .setMessage("Grant access to read GoPro videos from your device storage.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
