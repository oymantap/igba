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

    private var selectedPosition = 0

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
        val isSelected = (position == selectedPosition)

        holder.tvTitle.text = game.title

        if (game.coverBitmap != null) {
            holder.imgCover.setImageBitmap(game.coverBitmap)
        } else {
            holder.imgCover.setImageBitmap(generateLetterCover(game.title))
        }

        // Terapkan efek scaling & elevasi
        applySelectionAnimation(holder.itemView, isSelected, animate = false)

        holder.itemView.setOnClickListener {
            val currentPos = holder.bindingAdapterPosition
            if (currentPos != RecyclerView.NO_POSITION) {
                if (selectedPosition != currentPos) {
                    val oldPos = selectedPosition
                    selectedPosition = currentPos
                    notifyItemChanged(oldPos)
                    notifyItemChanged(selectedPosition)
                    onItemFocused(game)
                } else {
                    onItemClick(game)
                }
            }
        }

        holder.itemView.setOnLongClickListener {
            onItemLongClick(game)
            true
        }

        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val currentPos = holder.bindingAdapterPosition
                if (currentPos != RecyclerView.NO_POSITION && selectedPosition != currentPos) {
                    val oldPos = selectedPosition
                    selectedPosition = currentPos
                    notifyItemChanged(oldPos)
                    notifyItemChanged(selectedPosition)
                    onItemFocused(game)
                }
            }
        }
    }

    override fun getItemCount(): Int = games.size

    private fun applySelectionAnimation(view: View, isSelected: Boolean, animate: Boolean) {
        val targetScale = if (isSelected) 1.15f else 1.0f
        val targetElevation = if (isSelected) 16f else 4f
        val targetAlpha = if (isSelected) 1.0f else 0.75f

        if (animate) {
            view.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .translationZ(targetElevation)
                .alpha(targetAlpha)
                .setDuration(180)
                .start()
        } else {
            view.scaleX = targetScale
            view.scaleY = targetScale
            view.translationZ = targetElevation
            view.alpha = targetAlpha
        }
    }

    private fun generateLetterCover(title: String): Bitmap {
        val width = 300
        val height = 300
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paintGradient = Paint().apply {
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                Color.parseColor("#1E3C72"), Color.parseColor("#2A5298"),
                Shader.TileMode.CLAMP
            )
        }
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
