import AVFoundation
import Foundation
import Shared

/// `SoundPlayer`: one shared `AVAudioPlayer` so two alerts can never overlap (see the
/// "two alerts / single looping player" edge case). Only usable while the app is in the
/// foreground — when it is not, the doorbell comes from the notification sound instead.
final class IosSoundPlayer: NSObject, Shared.SoundPlayer {

    private var player: AVAudioPlayer?
    private let queue = DispatchQueue(label: "dev.aliziwa.jukizareminder.sound")

    func playDoorbell(loop: Bool) {
        queue.async { [weak self] in
            guard let self else { return }
            guard let url = Bundle.main.url(forResource: "doorbell", withExtension: "caf") else {
                NSLog("[todo] doorbell.caf missing from bundle")
                return
            }
            do {
                let session = AVAudioSession.sharedInstance()
                try session.setCategory(.playback, mode: .default, options: [])
                try session.setActive(true, options: [])

                if self.player == nil {
                    self.player = try AVAudioPlayer(contentsOf: url)
                    self.player?.prepareToPlay()
                }
                self.player?.numberOfLoops = loop ? -1 : 0
                self.player?.currentTime = 0
                self.player?.play()
            } catch {
                // No audio route / device busy must never crash the app.
                NSLog("[todo] doorbell playback failed: \(error.localizedDescription)")
            }
        }
    }

    func stop() {
        queue.async { [weak self] in
            self?.player?.stop()
            self?.player?.currentTime = 0
            try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        }
    }
}
