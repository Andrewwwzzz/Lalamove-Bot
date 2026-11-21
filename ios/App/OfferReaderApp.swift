import SwiftUI
import ReplayKit
import UserNotifications

@main
struct OfferReaderApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

/// Apple's own Start/Stop broadcast button, pointed at our screen-reading extension.
struct BroadcastButton: UIViewRepresentable {
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let v = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 72, height: 72))
        v.preferredExtension = (Bundle.main.bundleIdentifier ?? "com.zx.offerreader") + ".broadcast"
        v.showsMicrophoneButton = false
        return v
    }
    func updateUIView(_ uiView: RPSystemBroadcastPickerView, context: Context) {}
}

struct ContentView: View {
    @State private var notificationsOK = true
    @AppStorage("goalName") private var goalName = ""
    @AppStorage("goalBy") private var goalBy: Double = 0
    @State private var placeInput = ""
    @State private var timeInput = Date().addingTimeInterval(3600)

    private var goalActive: Bool { !goalName.isEmpty && goalBy > Date().timeIntervalSince1970 - 1800 }
    private var goalLine: String {
        guard goalActive else { return "GOAL: NONE" }
        let f = DateFormatter(); f.dateFormat = "HH:mm"
        return "GOAL: \(goalName) BY \(f.string(from: Date(timeIntervalSince1970: goalBy)))"
    }

    var version: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "\(v) (\(b))"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Offer Reader").font(.largeTitle.bold())
                Text("Reads Lalamove offers on your screen and notifies you about single-stop jobs within 3 km that pay at least S$14/hr net. It never taps anything. You still accept in Lalamove.")
                    .foregroundStyle(.secondary)

                // The reader picks this line up off the screen, so keep it plain and big.
                Text(goalLine)
                    .font(.system(size: 22, weight: .bold, design: .monospaced))
                    .lineLimit(1).minimumScaleFactor(0.5)   // one line, so the reader sees it whole
                    .foregroundStyle(Color.black)
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Color.white))
                    .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color.orange, lineWidth: 2))

                VStack(alignment: .leading, spacing: 8) {
                    Text("Be somewhere by a time (optional)").font(.headline)
                    TextField("Where, e.g. Paya Lebar", text: $placeInput)
                        .textFieldStyle(.roundedBorder)
                        .autocorrectionDisabled()
                    DatePicker("By", selection: $timeInput, displayedComponents: .hourAndMinute)
                    HStack {
                        Button("Set goal") {
                            let p = placeInput.trimmingCharacters(in: .whitespaces)
                            guard !p.isEmpty else { return }
                            var by = timeInput
                            if by < Date() { by = by.addingTimeInterval(24 * 3600) }
                            goalName = p; goalBy = by.timeIntervalSince1970; placeInput = ""
                        }
                        .buttonStyle(.borderedProminent).tint(.orange)
                        Button("Clear") { goalName = ""; goalBy = 0 }
                            .buttonStyle(.bordered)
                    }
                    Text("While the broadcast is running, come back to this screen for 2–3 seconds after changing the goal. The reader picks it up from the line above and confirms with a 🎯 notification.")
                        .font(.footnote).foregroundStyle(.secondary)
                }

                VStack(spacing: 8) {
                    BroadcastButton()
                        .frame(width: 72, height: 72)
                        .background(Circle().fill(Color.orange.opacity(0.18)))
                    Text("Tap the circle → Start Broadcast").font(.headline)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)

                if !notificationsOK {
                    Text("⚠️ Notifications are off. Turn them on in Settings → Notifications → Offer Reader, or you won't see any offers.")
                        .foregroundStyle(.red)
                }

                Group {
                    Text("How to use").font(.title3.bold())
                    Text("1. Tap the circle above, make sure Offer Reader is selected, then Start Broadcast. A red indicator shows at the top while it runs.")
                    Text("2. Open Lalamove → Pickup. Good offers pop up as banners: ✅ TAKE (S$17.50/hr+) or 🟡 OK (S$14–17.50/hr), with net earnings, pickup km vs max, and min fare.")
                    Text("3. After accepting a job, open Record → Ongoing for a second. Your jobs sync, and new offers are scored as stacks on top of them.")
                    Text("With a goal set, offers heading towards it score higher (the km you'd drive anyway are free), banners show minutes to spare, and anything that would make you late is skipped.")
                    Text("4. To stop, tap the red indicator, or come back here and tap the circle.")
                    Text("Take-home = 78.5% of fare + 94% of priority fee. Petrol S$0.25/km, other costs S$1/hr, 25 km/h + 5 min per stop. Distances are estimates; uses Lalamove's own pickup distance.")
                        .font(.footnote).foregroundStyle(.secondary)
                    Text("Version \(version)").font(.footnote).foregroundStyle(.secondary)
                }
            }
            .padding(20)
        }
        .onAppear {
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { ok, _ in
                DispatchQueue.main.async { notificationsOK = ok }
            }
        }
    }
}
