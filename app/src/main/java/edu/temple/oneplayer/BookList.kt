package edu.temple.oneplayer

class BookList {

    private val bookList : ArrayList<Book> by lazy {
        ArrayList()
    }

    fun add(book: Book) {
        bookList.add(book)
    }

    fun clear() {
        bookList.clear()
    }

    operator fun get(index: Int) = bookList[index]

    fun size() = bookList.size
}