import Foundation
import Shared
import UIKit

/// `PlatformCapabilities` for iOS. Launch-at-login does not exist on iOS (the OS decides),
/// so those two stay `false`; `bringToFront` is a no-op because iOS gives apps no way to
/// foreground themselves (notifications do that instead).
final class IosCapabilities: NSObject, Shared.PlatformCapabilities {

    var platformName: String { "iOS" }

    /// "1.0 (1)" — marketing version plus build, for Settings → About.
    var appVersion: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? ""
        let build = info?["CFBundleVersion"] as? String ?? ""
        if short.isEmpty { return build }
        return build.isEmpty ? short : "\(short) (\(build))"
    }

    /// iOS has no launch-at-login; the Settings card stays hidden.
    var supportsLaunchAtLogin: Bool { false }

    func bringToFront() {
        // Not possible on iOS.
    }

    func openUrl(url: String) {
        guard let target = URL(string: url) else { return }
        DispatchQueue.main.async {
            UIApplication.shared.open(target, options: [:], completionHandler: nil)
        }
    }

    func setLaunchAtLogin(enabled: Bool, completionHandler: @escaping (KotlinBoolean?, Error?) -> Void) {
        completionHandler(KotlinBoolean(bool: false), nil)
    }

    func isLaunchAtLoginEnabled(completionHandler: @escaping (KotlinBoolean?, Error?) -> Void) {
        completionHandler(KotlinBoolean(bool: false), nil)
    }

    /// Used by the model downloader to refuse a download that cannot fit.
    func freeSpaceBytes(directory: String, completionHandler: @escaping (KotlinLong?, Error?) -> Void) {
        do {
            let values = try URL(fileURLWithPath: directory)
                .resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            if let bytes = values.volumeAvailableCapacityForImportantUsage {
                completionHandler(KotlinLong(longLong: Int64(bytes)), nil)
                return
            }
            let attrs = try FileManager.default.attributesOfFileSystem(forPath: directory)
            if let free = attrs[.systemFreeSize] as? NSNumber {
                completionHandler(KotlinLong(longLong: free.int64Value), nil)
                return
            }
            completionHandler(nil, nil)
        } catch {
            completionHandler(nil, nil)
        }
    }
}
