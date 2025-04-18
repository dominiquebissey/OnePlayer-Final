package edu.temple.oneplayer

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton


class BookControlFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_book_control, container, false).apply {
            findViewById<ImageButton>(R.id.playButton).setOnClickListener {(activity as BookControlInterface).playBook()}
            findViewById<ImageButton>(R.id.pauseButton).setOnClickListener {(activity as BookControlInterface).pauseBook()}
        }
    }

    interface BookControlInterface {
        fun playBook()
        fun pauseBook()
    }
}