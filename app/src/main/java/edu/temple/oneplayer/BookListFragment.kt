package edu.temple.oneplayer

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class BookListFragment : Fragment() {

    private lateinit var bookViewModel : BookViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bookViewModel = ViewModelProvider(requireActivity())[BookViewModel::class.java]
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_book_list, container, false)
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val onClick: (Book) -> Unit = {
            // Update the ViewModel
                book: Book ->
            bookViewModel.setSelectedBook(book)
            // Inform the activity of the selection so as to not have the event replayed
            // when the activity is restarted
        }

        with(view as RecyclerView) {
            layoutManager = LinearLayoutManager(requireActivity())

            adapter = BookListAdapter(bookViewModel.bookList, onClick)

            bookViewModel.getUpdatedBookList().observe(requireActivity()) {
                adapter?.notifyDataSetChanged()
            }
        }

    }

    class BookListAdapter (_bookList: BookList, _onClick: (Book) -> Unit) : RecyclerView.Adapter<BookListAdapter.BookViewHolder>() {
        private val bookList = _bookList
        private val onClick = _onClick

        inner class BookViewHolder (layout : View): RecyclerView.ViewHolder (layout) {
            val titleTextView : TextView = layout.findViewById(R.id.titleTextView)
            val authorTextView: TextView = layout.findViewById(R.id.authorTextView)

            init {
                layout.setOnClickListener {
                    onClick(bookList[adapterPosition])
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
            return BookViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.booklist_items_layout, parent, false))
        }

        // Bind the book to the holder along with the values for the views
        override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
            holder.titleTextView.text = bookList[position].title
            holder.authorTextView.text = bookList[position].author
        }

        override fun getItemCount(): Int {
            return bookList.size()
        }

    }

}