package edu.temple.oneplayer

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.json.JSONArray
import kotlin.text.clear

class BookViewModel : ViewModel() {

    val bookList: BookList by lazy {
        BookList()
    }

    private val selectedBook: MutableLiveData<Book>? by lazy {
        MutableLiveData()
    }

    private val playingBook: MutableLiveData<Book>? by lazy {
        MutableLiveData()
    }

    // This item serves only as a notifier. We don't actually
    // care about the data it's storing. It's just a means to
    // have an observer be notified that something (new books have been added)
    // has happened
    private val updatedBookList : MutableLiveData<Int> by lazy {
        MutableLiveData()
    }

    // Flag to determine if one-off event should fire
    private var viewedBook = false

    // Flag to determine if one-off event should fire
    private var playedBook = false

    fun getSelectedBook(): LiveData<Book>? {
        return selectedBook
    }

    fun setSelectedBook(selectedBook: Book) {
        viewedBook = false
        this.selectedBook?.value = selectedBook
    }

    fun clearSelectedBook () {
        selectedBook?.value = null
    }

    fun markSelectedBookViewed () {
        viewedBook = true
    }

    fun hasViewedSelectedBook() : Boolean {
        return viewedBook
    }

    fun getPlayingBook(): LiveData<Book>? {
        return playingBook
    }

    fun setBookPlayed (state: Boolean) {
        playedBook = state
    }

    fun hasBookBeenPlayed(): Boolean {
        return playedBook
    }

    fun setPlayingBook(playingBook: Book) {
        this.playingBook?.value = playingBook
    }

    fun updateBooks (books: JSONArray) {
        bookList.clear()
        for (i in 0 until books.length()) {
            bookList.add(Book(books.getJSONObject(i)))
        }
        notifyUpdatedBookList()
    }

    // The indirect observable for those that want to know when
    // the book list has changed
    fun getUpdatedBookList() : LiveData<out Any> {
        return updatedBookList
    }

    // A trivial update used to indirectly notify observers that the Booklist has changed
    private fun notifyUpdatedBookList() {
        updatedBookList.value = updatedBookList.value?.plus(1)
    }
}