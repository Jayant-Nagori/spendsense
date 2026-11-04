import SwiftUI
import UserNotifications
import Shared

/// Hosts the shared Compose UI (`MainViewController`) full screen; Compose handles safe-area insets itself.
@main
struct SpendSenseApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @Environment(\.scenePhase) private var scenePhase
    @State private var dark = true

    var body: some Scene {
        WindowGroup {
            ComposeHost()
                .ignoresSafeArea()
                .preferredColorScheme(dark ? .dark : .light)
                .onAppear {
                    IosApp.shared.onThemeChanged = { isDark in
                        DispatchQueue.main.async { dark = isDark.boolValue }
                    }
                }
                .onOpenURL { url in handleDebugURL(url) }
                // App-switcher snapshot / screen sharing while inactive shows a plain cover, never balances or payments.
                .overlay { if scenePhase != .active { PrivacyCover() } }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: IosApp.shared.onForeground()
            case .background: IosApp.shared.onBackground()
            default: break
            }
        }
    }

    /// Debug builds only: `spendsense://log?text=...&sender=...` runs the same path as the App Intent (for QA
    /// screenshots on the simulator, which has no Messages automations). Release builds ignore every URL so no web
    /// page can inject transactions.
    private func handleDebugURL(_ url: URL) {
        #if DEBUG
        if url.host == "debug" {
            IosApp.shared.debugLink(url: url.absoluteString)
            return
        }
        guard url.host == "log",
              let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems,
              let text = items.first(where: { $0.name == "text" })?.value else { return }
        let sender = items.first(where: { $0.name == "sender" })?.value
        DispatchQueue.global(qos: .userInitiated).async {
            _ = IosApp.shared.logBankSms(text: text, sender: sender)
        }
        #endif
    }
}

/// Opaque cover drawn whenever the scene is not active (iOS takes the app-switcher snapshot then).
struct PrivacyCover: View {
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            Text("SpendSense").font(.headline).foregroundColor(.white.opacity(0.7))
        }
        .accessibilityHidden(true)
    }
}

struct ComposeHost: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { IosAppKt.MainViewController() }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        IosApp.shared.start()
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
        return true
    }

    // While SpendSense is open the pop-up is already on screen; don't stack a banner on top of it.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.list])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        if let raw = response.notification.request.content.userInfo["txnId"] as? String, let id = Int64(raw) {
            IosApp.shared.openTransaction(txnId: id)
        }
        completionHandler()
    }
}
