package com.plcoding.MusicPlayer.ui

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.hardware.biometrics.BiometricPrompt
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.RequestManager
import com.google.android.material.snackbar.Snackbar
import com.plcoding.MusicPlayer.R
import com.plcoding.MusicPlayer.adapters.SwipeSongAdapter
import com.plcoding.MusicPlayer.data.entities.Song
import com.plcoding.MusicPlayer.exoplayer.isPlaying
import com.plcoding.MusicPlayer.exoplayer.toSong
import com.plcoding.MusicPlayer.other.Status
import com.plcoding.MusicPlayer.ui.viewmodels.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.activity_main.*
import javax.inject.Inject
import android.os.CancellationSignal
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    @Inject
    lateinit var swipeSongAdapter: SwipeSongAdapter

    @Inject
    lateinit var glide: RequestManager

    private var curPlayingSong: Song? = null

    private var playbackState: PlaybackStateCompat? = null

    private var cancellationSignal: CancellationSignal? = null
    private val authenticationCallback: BiometricPrompt.AuthenticationCallback
    get() =
        @RequiresApi(Build.VERSION_CODES.P)
        object: BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                super.onAuthenticationError(errorCode, errString)
                notifyUser("Authentication Error: $errString")
                finishAffinity()
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                super.onAuthenticationSucceeded(result)
                notifyUser("Authentication succeeded")
            }
        }


    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        subscribeToObservers()

        vpSong.adapter = swipeSongAdapter

        vpSong.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback(){
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if(playbackState?.isPlaying == true){
                    mainViewModel.playOrToggleSong(swipeSongAdapter.songs[position])
                } else {
                    curPlayingSong = swipeSongAdapter.songs[position]
                }
            }
        })

        ivPlayPause.setOnClickListener {
            curPlayingSong?.let {
                mainViewModel.playOrToggleSong(it, true)
            }
        }

        swipeSongAdapter.setItemClickListener {
            navHostFragment.findNavController().navigate(
                R.id.globleActionToSongFragment
            )
        }

        navHostFragment.findNavController().addOnDestinationChangedListener { _, destination, _->
            when(destination.id){
                R.id.songFragment -> hideBottomBar()
                R.id.homeFragment -> showBottomBar()
                else -> showBottomBar()
            }
        }

        checkBiometricSupport()
        val biometricPrompt = BiometricPrompt.Builder(this)
            .setTitle("Fingerprint Sensor")
            .setDescription("This app uses fingerprint authentication to keep your data secure")
            .setNegativeButton("Cancel", this.mainExecutor, DialogInterface.OnClickListener{dialog, which -> finishAffinity()}).build()

        biometricPrompt.authenticate(getCancellationSignal(), mainExecutor, authenticationCallback)
    }

    private fun hideBottomBar(){
        ivCurSongImage.isVisible = false
        vpSong.isVisible = false
        ivPlayPause.isVisible = false
    }

    private fun showBottomBar(){
        ivCurSongImage.isVisible = true
        vpSong.isVisible = true
        ivPlayPause.isVisible = true
    }

    private fun switchViewPagerToCurrentSong(song: Song) {
        val newItemIndex = swipeSongAdapter.songs.indexOf(song)
        if(newItemIndex != -1){
            vpSong.currentItem =newItemIndex
            curPlayingSong = song
        }
    }

    private fun subscribeToObservers() {
        mainViewModel.mediaItems.observe(this){
            it?.let { result ->
                when(result.status){
                    Status.SUCCESS -> {
                        result.data?.let { songs ->
                            swipeSongAdapter.songs = songs
                            if(songs.isNotEmpty()) {
                                glide.load((curPlayingSong ?: songs[0]).imageUrl).into(ivCurSongImage)
                            }
                            switchViewPagerToCurrentSong(curPlayingSong ?: return@observe)
                        }
                    }
                    Status.ERROR -> Unit
                    Status.LOADING -> Unit

                }
            }
        }
        mainViewModel.curPlayingSong.observe(this){
            if(it == null) return@observe

            curPlayingSong =it.toSong()
            glide.load(curPlayingSong?.imageUrl).into(ivCurSongImage)
            switchViewPagerToCurrentSong(curPlayingSong ?: return@observe)

        }
        mainViewModel.playbackState.observe(this) {
            playbackState = it
            ivPlayPause.setImageResource(
                if(playbackState?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play
            )
        }
        mainViewModel.isConnected.observe(this){
            it?.getContentIfNotHandled()?.let{ result ->
                when(result.status){
                    Status.ERROR -> Snackbar.make(
                        rootLayout,
                        result.message ?: "An Unknown Error Occured",
                        Snackbar.LENGTH_LONG
                    ).show()
                    else -> Unit
                }
            }
        }
        mainViewModel.networkError.observe(this){
            it?.getContentIfNotHandled()?.let{ result ->
                when(result.status){
                    Status.ERROR -> Snackbar.make(
                        rootLayout,
                        result.message ?: "An Unknown Error Occured",
                        Snackbar.LENGTH_LONG
                    ).show()
                    else -> Unit
                }
            }
        }
    }

    private fun notifyUser(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun getCancellationSignal(): CancellationSignal {
        cancellationSignal = CancellationSignal()
        cancellationSignal?.setOnCancelListener {
            notifyUser("Authentication was cancelled by the user")
        }

        return cancellationSignal as CancellationSignal
    }

    private fun checkBiometricSupport(): Boolean {

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager

        if(!keyguardManager.isKeyguardSecure) {
            notifyUser("Fingerprint Authentication has not been enabled in settings")
            return false
        }

        if(ActivityCompat.checkSelfPermission(this, android.Manifest.permission.USE_BIOMETRIC) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifyUser("Fingerprint Authentication permission is not enabled")
            return false
        }

        return if(packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FINGERPRINT)) {
            true
        } else true
    }
}