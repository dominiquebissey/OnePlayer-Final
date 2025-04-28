package edu.temple.oneplayer

import android.animation.Animator.AnimatorPauseListener
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
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.time.Duration as JavaDuration

class MainActivity : AppCompatActivity(), BookControlFragment.BookControlInterface  {
    lateinit var backPressCallback: OnBackPressedCallback

    private val searchURL = "https://kamorris.com/lab/audlibplayer/searchbooks.php?query="
    private val downloadURL = "https://kamorris.com/lab/oneplayer/downloadbook.php?id="

    private val PREFS_NAME = "PlaybackState"
    private val POSITION_KEY_PREFIX = "playback_position_"
    private val PAUSE_TIME_KEY_PREFIX = "pause_time_"
    private val LAST_SEARCH_IDS_KEY = "last_search_book_ids"
    private lateinit var sharedPreferences: SharedPreferences

    private lateinit var progressSeekBar: SeekBar
    lateinit var bookServiceIntent: Intent

    var mediaControllerBinder: AudioBookPlayerService.MediaControlBinder? = null
    private var currentPlayingBook: Book? = null
    private var latestPlaybackPosition: Int = 0
    private var lastPauseTime: LocalDateTime? = null

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
                currentPlayingBook = book as Book
            }

            // Update seekbar with progress of current book as a percentage
            progressSeekBar.progress = ((progress.toFloat() / (book as Book).duration) * 100).toInt()
            latestPlaybackPosition = progress
        }
        true
    }

    // Callback that is invoked when (un)binding is complete
    private val bookServiceConnection = object: ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            mediaControllerBinder = service as AudioBookPlayerService.MediaControlBinder
            mediaControllerBinder?.setProgressHandler(bookProgressHandler)
            currentPlayingBook?.let { restorePlayback(it) }
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

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
                                    latestPlaybackPosition = ((progress.toFloat() / 100) * book.duration).toInt()
                                    savePlaybackPosition(book, latestPlaybackPosition)
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

        loadLastSearchResults()

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
            } else {
                currentPlayingBook?.let {
                    savePlaybackPosition(it, latestPlaybackPosition)
                }
                currentPlayingBook = it
            }
        }

        // Always show currently playing book
        bookViewModel.getPlayingBook()?.observe(this){
            nowPlayingTextView.text = String.format(getString(R.string.now_playing), it.title)
            currentPlayingBook = it
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
                {
                    bookViewModel.updateBooks(it)
                    saveLastSearchResults(it)
                },
                { Toast.makeText(this, it.networkResponse.toString(), Toast.LENGTH_SHORT).show() })
        )
    }

    private fun saveLastSearchResults(jsonArray: JSONArray) {
        val bookIds = mutableListOf<Int>()
        for (i in 0 until jsonArray.length()) {
            val book = jsonArray.getJSONObject(i)
            bookIds.add(book.getInt("book_id"))
        }
        val idsString = bookIds.joinToString(",")
        sharedPreferences.edit().putString(LAST_SEARCH_IDS_KEY, idsString).apply()
        Log.d("SearchPersistence", "Saved last search IDs: $idsString")
    }

    private fun loadLastSearchResults() {
        val savedIdsString = sharedPreferences.getString(LAST_SEARCH_IDS_KEY, null)
        if (!savedIdsString.isNullOrEmpty()) {
            val bookIds = savedIdsString.split(",").mapNotNull { it.toIntOrNull() }
            if (bookIds.isNotEmpty()) {
                Log.d("SearchPersistence", "Loading last search IDs: $bookIds")
                fetchAllBooksForPersistedSearch(bookIds)
            }
        } else {
            Log.d("SearchPersistence", "No last search results found")
        }
    }

    private fun fetchAllBooksForPersistedSearch(targetIds: List<Int>) {
        requestQueue.add (
            JsonArrayRequest(searchURL + "",
                { response ->
                    val allFetchedBooks = BookList()
                    for (i in 0 until response.length()) {
                        allFetchedBooks.add(Book(response.getJSONObject(i)))
                    }
                    bookViewModel.setLastSearchBooks(targetIds, allFetchedBooks)
                    Log.d(
                        "SearchPersistence",
                        "fetched all books for persisted search using empty query."
                    )
                },
                { error->
                    Toast.makeText(this, "Error fetching all books: ${error.message}", Toast.LENGTH_SHORT).show()
            })
        )
    }

    override fun playBook() {
        bookViewModel.getSelectedBook()?.value?.apply {
            val localFile = File(downloadDirectory, "${book_id}.mp3")

            if (localFile.exists()) {
                val savedPosition = getSavedPlaybackPositionWithRewind(this)
                mediaControllerBinder?.run {
                    bookViewModel.setBookPlayed(false)
                    play(this@apply.apply { bookFile = localFile }, savedPosition)
                    startService(bookServiceIntent)
                }
                Toast.makeText(this@MainActivity, "Playing from downloaded file (position: $savedPosition)", Toast.LENGTH_SHORT).show()
            } else {
                mediaControllerBinder?.run {
                    bookViewModel.setBookPlayed(false)
                    play(this@apply)
                    startService(bookServiceIntent)
                }
                Toast.makeText(this@MainActivity, "Streaming and downloading...", Toast.LENGTH_SHORT).show()
                downloadBookInBackground(this)
            }
            currentPlayingBook = this
        }
    }

    override fun pauseBook() {
        bookViewModel.getPlayingBook()?.value?.let { book->
            savePlaybackPosition(book, latestPlaybackPosition)
            lastPauseTime = LocalDateTime.now(ZoneOffset.UTC)
            savePauseTime(book, lastPauseTime)
        }

        mediaControllerBinder?.run {
            if (isPlaying) {
                stopService(bookServiceIntent)
            } else {
                startService(bookServiceIntent)
            }
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

    private fun savePlaybackPosition(book: Book, position: Int) {
        sharedPreferences.edit()
            .putInt(POSITION_KEY_PREFIX + book.book_id, position)
            .apply()
        Log.d("PlaybackState", "Saved position $position for book ${book.title} (${book.book_id})")
    }

    private fun getSavedPlaybackPosition(book: Book): Int {
        return sharedPreferences.getInt(POSITION_KEY_PREFIX + book.book_id, 0)
    }

    private fun savePauseTime(book: Book, pauseTime: LocalDateTime?) {
        pauseTime?.let {
            sharedPreferences.edit()
                .putString(PAUSE_TIME_KEY_PREFIX + book.book_id, it.toString())
                .apply()
            Log.d("PlaybackState", "Saved pause time for book ${book.title} (${book.book_id}): $it")
        }
    }

    private fun getSavedPauseTime(book: Book): LocalDateTime? {
        val pauseTimeString = sharedPreferences.getString(PAUSE_TIME_KEY_PREFIX + book.book_id, null)
        return try {
            LocalDateTime.parse(pauseTimeString)
        } catch (e: Exception) {
            null
        }
    }

    private fun getSavedPlaybackPositionWithRewind(book: Book): Int {
        val savedPosition = getSavedPlaybackPosition(book)
        val pauseTime = getSavedPauseTime(book)

        if (pauseTime != null) {
            val now = LocalDateTime.now(ZoneOffset.UTC)
            val durationSincePause = JavaDuration.between(pauseTime, now)
            val elapsedTimeSeconds = durationSincePause.toSeconds()

            val rewindSeconds = when {
                elapsedTimeSeconds < 60 -> 5
                elapsedTimeSeconds < 300 -> 15
                elapsedTimeSeconds < 3600 -> 30
                else -> 60
            }

            val newPosition = (savedPosition - rewindSeconds).coerceAtLeast(0)
            Log.d("PlaybackState", "Rewinding by $rewindSeconds seconds. Saved position: $savedPosition, new position: $newPosition")
            return newPosition
        } else {
            Log.d("PlaybackState", "No saved pause time found. Starting from saved position: $savedPosition")
            return savedPosition
        }
    }

    private fun restorePlayback(book: Book) {
        val localFile = File(downloadDirectory, "${book.book_id}.mp3")
        if (localFile.exists()) {
            val savedPosition = getSavedPlaybackPosition(book)
            mediaControllerBinder?.run {
                bookViewModel.setBookPlayed(true)
                play(book.apply { bookFile = localFile }, savedPosition)
                startService(bookServiceIntent)
            }
            Log.d("PlaybackState", "Restored position $savedPosition for book ${book.title} (${book.book_id})")
        }
    }

    override fun onDestroy() {
        currentPlayingBook?.let {
            savePlaybackPosition(it, latestPlaybackPosition)
            lastPauseTime = LocalDateTime.now(ZoneOffset.UTC)
            savePauseTime(it, lastPauseTime)
        }
        unbindService(bookServiceConnection)
        downloadExecutor.shutdown()
        super.onDestroy()
    }
}