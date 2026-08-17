package com.juan.asistenciaapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.juan.asistenciaapp.databinding.ActivitySplashBinding

/**
 * Pantalla de carga animada: el logo entra con fade + zoom dentro de un anillo
 * que "pulsa", el nombre se desliza y luego se desvanece todo hacia la app.
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        animarLogo()
        animarAnillo()
        animarTitulo()

        binding.rootSplash.postDelayed({
            binding.rootSplash.animate()
                .alpha(0f)
                .setDuration(400)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    if (!isFinishing && !isDestroyed) {
                        startActivity(Intent(this, MainActivity::class.java))
                        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
                        finish()
                    }
                }
                .start()
        }, 1750)
    }

    /** Logo: entra con un zoom desde pequeño. */
    private fun animarLogo() {
        val img: View = binding.imgSplash
        img.alpha = 0f
        img.scaleX = 0.6f
        img.scaleY = 0.6f
        img.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(900)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Anillo: se expande hacia afuera y se desvanece (efecto pulso). */
    private fun animarAnillo() {
        val anillo: View = binding.anillo
        anillo.alpha = 0f
        anillo.scaleX = 0.55f
        anillo.scaleY = 0.55f
        anillo.animate()
            .alpha(0.9f)
            .scaleX(1.05f)
            .scaleY(1.05f)
            .setDuration(800)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                anillo.animate()
                    .alpha(0f)
                    .scaleX(1.35f)
                    .scaleY(1.35f)
                    .setDuration(500)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    /** Título: sube y aparece con un pequeño retraso. */
    private fun animarTitulo() {
        val titulo: View = binding.tvNombreSplash
        titulo.alpha = 0f
        titulo.translationY = 24f
        titulo.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(500)
            .setDuration(650)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}
