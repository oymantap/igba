package com.rycl.igba

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class Ps4GameAdapter(
    private val games: List<GameModel>,
    private val onItemClick: (GameModel) -> Unit,
    private val onItemLongClick: (GameModel) -> Unit,
    private val onItemFocused: (GameModel) -> Unit
) : RecyclerView.Adapter<Ps4GameAdapter.GameViewHolder>() {

    class GameViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imgCover: ImageView = itemView.findViewById(R.id.img_game_cover)
        val tvTitle: TextView = itemView.findViewById(R.id.tv_game_item_title)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GameViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ps4_game, parent, false)
        return GameViewHolder(view)
    }

    override fun onBindViewHolder(holder: GameViewHolder, position: Int) {
        val game = games[position]
        holder.tvTitle.text = game.title

        if (game.coverBitmap != null) {
            holder.imgCover.setImageBitmap(game.coverBitmap)
        } else {
            val generatedCover = generateLetterCover(game.title)
            holder.imgCover.setImageBitmap(generatedCover)
        }

        holder.itemView.setOnClickListener { onItemClick(game) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(game)
            true
        }
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                holder.itemView.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start()
                onItemFocused(game)
            } else {
                holder.itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
            }
        }
    }

    override fun getItemCount(): Int = games.size

    private fun generateLetterCover(title: String): Bitmap {
        val width = 300
        val height = 300
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paintGradient = Paint()
        val gradient = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            Color.parseColor("#1E3C72"), Color.parseColor("#2A5298"),
            Shader.TileMode.CLAMP
        )
        paintGradient.shader = gradient
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintGradient)

        val initialLetter = if (title.isNotBlank()) title.substring(0, 1).uppercase(Locale.ROOT) else "G"
        val paintText = Paint().apply {
            color = Color.WHITE
            textSize = 120f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        val yPos = (canvas.height / 2f) - ((paintText.descent() + paintText.ascent()) / 2f)
        canvas.drawText(initialLetter, canvas.width / 2f, yPos, paintText)

        return bitmap
    }
}
