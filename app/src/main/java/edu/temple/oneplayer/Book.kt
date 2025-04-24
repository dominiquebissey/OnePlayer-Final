package edu.temple.oneplayer
import edu.temple.flossaudioplayer.AudioBookPlayerService
import org.json.JSONObject
import java.io.File

const val ID = "book_id"
const val TITLE = "book_title"
const val AUTHOR = "author_name"
const val COVER = "cover_uri"
const val DURATION = "duration"
const val FILE = "file_url"

data class Book (val book_id: Int, val title: String, val author: String, val coverUri: String, val duration: Int,
                 var bookFile: File? = null, private val fileUrl: String? = null) : AudioBookPlayerService.AudioBook, AudioBookPlayerService.FileAudioBook  {

    constructor(book: JSONObject) : this(
        book.getInt(ID),
        book.getString(TITLE),
        book.getString(AUTHOR),
        book.getString(COVER),
        book.getInt(DURATION),
        fileUrl = book.optString(FILE, null)
    )

    override fun getAudioBookId(): Int {
        return book_id
    }

    override fun getFile(): File? {
        return bookFile
    }
}