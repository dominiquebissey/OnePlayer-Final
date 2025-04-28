package edu.temple.oneplayer

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.android.volley.RequestQueue
import com.android.volley.toolbox.JsonArrayRequest
import com.android.volley.toolbox.Volley
import edu.temple.flossaudioplayer.AudioBookPlayerService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), BookControlFragment.BookControlInterface  {
    lateinit var backPressCallback: OnBackPressedCallback

    private val searchURL = "https://kamorris.com/lab/audlibplayer/searchbooks.php?query="
    private val downloadURL = "https://kamorris.com/lab/oneplayer/downloadbook.php?id="

    private lateinit var progressSeekBar: SeekBar
    lateinit var bookServiceIntent: Intent

    var mediaControllerBinder: AudioBookPlayerService.MediaControlBinder? = null

    private val downloadExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor()
    }

    private val downloadDirectory: File by lazy {
        File(getExternalFilesDir(null), "audiobooks")
            .apply { if (!exists()) mkdirs() }
    }

    val bookProgressHandler = Handler(Looper.getMainLooper()) {

        with (it.obj as AudioBookPlayerService.BookProgress) {

            // Update ViewModel state based on whether we're seeing the currently playing book
            // from the service for the first time
            if (!bookViewModel.hasBookBeenPlayed()) {
                bookViewModel.setBookPlayed(true)
                bookViewModel.setPlayingBook(book as Book)
                bookViewModel.setSelectedBook(book as Book)
            }

            // Update seekbar with progress of current book as a percentage
            progressSeekBar.progress = ((progress.toFloat() / (book as Book).duration) * 100).toInt()
        }
        true
    }

    // Callback that is invoked when (un)binding is complete
    private val bookServiceConnection = object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            mediaControllerBinder = service as AudioBookPlayerService.MediaControlBinder
            mediaControllerBinder?.setProgressHandler(bookProgressHandler)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mediaControllerBinder = null
        }

    }

    private val requestQueue : RequestQueue by lazy {
        Volley.newRequestQueue(this)
    }

    private val isSingleContainer : Boolean by lazy{
        findViewById<View>(R.id.container2) == null
    }

    private val bookViewModel : BookViewModel by lazy {
        ViewModelProvider(this)[BookViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val nowPlayingTextView = findViewById<TextView>(R.id.nowPlayingTextView)
        progressSeekBar = findViewById<SeekBar?>(R.id.progressSeekBar).apply {
            setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    seekBar: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    if (fromUser) {

                        // If the user is dragging the SeekBar, convert progress percentage
                        // to value in seconds and seek to position
                        bookViewModel.getSelectedBook()?.value?.let {book ->
                            mediaControllerBinder?.run {
                                if (isPlaying) {
                                    seekTo(((progress.toFloat() / 100) * book.duration).toInt())
                                }
                            }
                        }
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                override fun onStopTrackingTouch(seekBar: SeekBar?) {}

            })
        }

        // Use Back gesture to clear selected book
        val backPressCallback = object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // BackPress clears the selected book
                bookViewModel.clearSelectedBook()
                Log.d("Back", "Pressed")
                // Remove responsibility for handling back gesture
                isEnabled = false
                // Trigger back gesture
                onBackPressedDispatcher.onBackPressed()
            }
        }

        onBackPressedDispatcher.addCallback(backPressCallback)

        // If we're switching from one container to two containers
        // clear BookPlayerFragment from container1
        if (supportFragmentManager.findFragmentById(R.id.container1) is BookPlayerFragment) {
            supportFragmentManager.popBackStack()
        }

        // If this is the first time the activity is loading, go ahead and add a BookListFragment
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.container1, BookListFragment())
                .commit()
        } else
        // If activity loaded previously, there's already a BookListFragment
        // If we have a single container and a selected book, place it on top
            if (isSingleContainer && bookViewModel.getSelectedBook()?.value != null) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.container1, BookPlayerFragment())
                    .setReorderingAllowed(true)
                    .addToBackStack(null)
                    .commit()
            }

        // If we have two containers but no BookPlayerFragment, add one to container2
        if (!isSingleContainer && supportFragmentManager.findFragmentById(R.id.container2) !is BookPlayerFragment)
            supportFragmentManager.beginTransaction()
                .add(R.id.container2, BookPlayerFragment())
                .commit()


        // Respond to selection in portrait mode using flag stored in ViewModel
        bookViewModel.getSelectedBook()?.observe(this){
            if (!bookViewModel.hasViewedSelectedBook()) {
                backPressCallback.isEnabled = true
                if (isSingleContainer) {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.container1, BookPlayerFragment())
                        .setReorderingAllowed(true)
                        .addToBackStack(null)
                        .commit()
                }
                bookViewModel.markSelectedBookViewed()
            }
        }

        // Always show currently playing book
        bookViewModel.getPlayingBook()?.observe(this){
            nowPlayingTextView.text = String.format(getString(R.string.now_playing), it.title)
        }

        findViewById<View>(R.id.searchImageButton).setOnClickListener {
            onSearchRequested()
        }

        bookServiceIntent = Intent(this, AudioBookPlayerService::class.java)

        // Bind in order to send commands
        bindService(bookServiceIntent, bookServiceConnection, BIND_AUTO_CREATE)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        if (Intent.ACTION_SEARCH == intent!!.action) {
            intent.getStringExtra(SearchManager.QUERY)?.also {
                searchBooks(it)

                // Unselect previous book selection
                bookViewModel.clearSelectedBook()

                // Remove any unwanted DisplayFragments instances from the stack
                supportFragmentManager.popBackStack()
            }
        }

    }

    private fun searchBooks(searchTerm: String) {
        requestQueue.add(
            JsonArrayRequest(searchURL + searchTerm,
                { bookViewModel.updateBooks(it) },
                { Toast.makeText(this, it.networkResponse.toString(), Toast.LENGTH_SHORT).show() })
        )
    }

    override fun playBook() {
        bookViewModel.getSelectedBook()?.value?.apply {
            val localFile = File(downloadDirectory, "${book_id}.mp3") // Assuming .mp3 format

            if (localFile.exists()) {
                // Play from local file
                mediaControllerBinder?.run {
                    bookViewModel.setBookPlayed(false)
                    play(this@apply.apply { bookFile = localFile }, 0) // Start from the beginning
                    startService(bookServiceIntent)
                }
                Toast.makeText(this@MainActivity, "Playing from downloaded file", Toast.LENGTH_SHORT).show()
            } else {
                // Stream and download in the background
                mediaControllerBinder?.run {
                    bookViewModel.setBookPlayed(false)
                    play(this@apply)
                    startService(bookServiceIntent)
                }
                Toast.makeText(this@MainActivity, "Streaming and downloading...", Toast.LENGTH_SHORT).show()
                downloadBookInBackground(this)
            }
        }
    }

    override fun pauseBook() {
        mediaControllerBinder?.run {
            if (isPlaying) stopService(bookServiceIntent)
            else startService(bookServiceIntent)
            pause()
        }
    }

    private fun downloadBookInBackground(book: Book) {
        downloadExecutor.execute {
            try {
                val downloadUrl = "${downloadURL}${book.book_id}"
                val url = URL(downloadUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.connect()

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val inputStream = connection.inputStream
                    val outputFile = File(downloadDirectory, "${book.book_id}.mp3")
                    val outputStream = FileOutputStream(outputFile)

                    val buffer = ByteArray(4096)
                    var bytesRead = -1
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.close()
                    inputStream.close()

                    // Update the Book object with the local file path (optional, for future use)
                    book.bookFile = outputFile
                    Log.d("Download", "Book ${book.title} downloaded to ${outputFile.absolutePath}")
                } else {
                    Log.e(
                        "Download",
                        "Failed to download book ${book.title}. Response code: ${connection.responseCode}"
                    )
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e("Download", "Error downloading book ${book.title}: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        unbindService(bookServiceConnection)
        downloadExecutor.shutdown()
        super.onDestroy()
    }
}