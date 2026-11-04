import AppIntents
import Shared

/// Called by a Shortcuts personal automation ("When I get a message containing 'debited' → Log bank SMS").
/// iOS apps cannot read SMS or other apps' notifications, so the automation forwards the message text here.
/// Runs the shared Kotlin parser + ingest, then opens SpendSense on the full-screen payment pop-up.
struct LogBankSmsIntent: AppIntent {
    static var title: LocalizedStringResource = "Log bank SMS"
    static var description = IntentDescription("Reads a bank / UPI / card SMS and logs the payment in SpendSense, then asks what it was for.")
    // Opening the app also guarantees the device is unlocked, so the protected database is readable.
    static var openAppWhenRun: Bool = true

    @Parameter(title: "Message text", inputOptions: String.IntentInputOptions(multiline: true))
    var text: String

    @Parameter(title: "Sender", description: "Optional SMS sender, e.g. AD-HDFCBK")
    var sender: String?

    static var parameterSummary: some ParameterSummary {
        Summary("Log \(\.$text) in SpendSense") { \.$sender }
    }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let body = text
        let from = sender
        let result = await Task.detached(priority: .userInitiated) {
            IosApp.shared.logBankSms(text: body, sender: from)
        }.value
        return .result(dialog: IntentDialog(stringLiteral: result))
    }
}

struct SpendSenseShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: LogBankSmsIntent(),
            phrases: ["Log bank SMS in \(.applicationName)", "Log a payment in \(.applicationName)"],
            shortTitle: "Log bank SMS",
            systemImageName: "indianrupeesign.circle"
        )
    }
}
