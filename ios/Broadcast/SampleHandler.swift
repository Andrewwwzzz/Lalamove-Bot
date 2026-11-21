import ReplayKit
import ImageIO
import Vision
import UserNotifications
import CoreLocation

/**
 Screen-broadcast extension. While the broadcast runs it reads the screen about once a second,
 finds Lalamove offers, scores them, and posts a notification for anything at or above the target.
 Read-only: it never taps anything. Everything it needs is stored in its own UserDefaults.
 */
class SampleHandler: RPBroadcastSampleHandler {

    private let target = 14.0                     // S$/hr net
    private let maxPickupKm = 3.0                 // ignore offers whose pickup is further than this
    private var lastRead = Date.distantPast
    private var notified = Set<String>()
    private let defaults = UserDefaults.standard
    private let lock = NSLock()
    private var geoCache: [String: LatLng] = [:]
    private var geoFailed = Set<String>()
    private var geoPending = Set<String>()
    private let geocoder = CLGeocoder()
    private var lastStatus = ""
    private var lastStatusAt = Date.distantPast

    private struct SavedOrder: Codable { let pickup: String; let drop: String; let fare: Double; let priority: Double; let saved: Date }

    // MARK: - Broadcast lifecycle

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        if let data = defaults.data(forKey: "geo"), let g = try? JSONDecoder().decode([String: LatLng].self, from: data) { geoCache = g }
        post(id: "status", title: "Offer Reader is on", body: "Open Lalamove → Pickup. Offers at S$\(Int(target))/hr+ will pop up here.")
    }

    override func broadcastFinished() {
        post(id: "status", title: "Offer Reader stopped", body: "Tap Start in the app to read offers again.")
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer, with type: RPSampleBufferType) {
        guard type == .video else { return }
        let now = Date()
        guard now.timeIntervalSince(lastRead) >= 1.0, let pb = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        lastRead = now

        var orientation = CGImagePropertyOrientation.up
        if let o = CMGetAttachment(sampleBuffer, key: RPVideoSampleOrientationKey as CFString, attachmentModeOut: nil) as? NSNumber,
           let ori = CGImagePropertyOrientation(rawValue: o.uint32Value) { orientation = ori }

        let w = CVPixelBufferGetWidth(pb), h = CVPixelBufferGetHeight(pb)
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .fast            // light on memory (extensions are capped at ~50 MB)
        request.usesLanguageCorrection = false
        let handler = VNImageRequestHandler(cvPixelBuffer: pb, orientation: orientation, options: [:])
        do { try handler.perform([request]) } catch { return }
        let obs = request.results ?? []

        let lines: [OcrLine] = obs.compactMap { o in
            guard let text = o.topCandidates(1).first?.string else { return nil }
            let b = o.boundingBox   // normalised, origin bottom-left
            return OcrLine(text: text,
                           left: Int(b.minX * CGFloat(w)), top: Int((1 - b.maxY) * CGFloat(h)),
                           right: Int(b.maxX * CGFloat(w)), bottom: Int((1 - b.minY) * CGFloat(h)))
        }
        handle(lines)
    }

    // MARK: - Reading

    // MARK: - Goal (read off the Offer Reader screen)

    private func readGoal(_ lines: [OcrLine]) {
        guard let g = OfferParser.goal(lines) else { return }
        if g.place.isEmpty {
            if defaults.string(forKey: "goalName") != nil {
                defaults.removeObject(forKey: "goalName")
                post(id: "goal", title: "🎯 Goal cleared", body: "Offers are scored on S$/hr only.")
            }
            return
        }
        var c = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        c.hour = g.hour; c.minute = g.minute
        guard var by = Calendar.current.date(from: c) else { return }
        if by < Date().addingTimeInterval(-30 * 60) { by = by.addingTimeInterval(24 * 3600) }
        if defaults.string(forKey: "goalName") == g.place && abs(defaults.double(forKey: "goalBy") - by.timeIntervalSince1970) < 60 { return }
        defaults.set(g.place, forKey: "goalName")
        defaults.set(by.timeIntervalSince1970, forKey: "goalBy")
        _ = resolve(g.place)
        post(id: "goal", title: "🎯 Goal set: \(g.place) by \(clock(by))", body: "Offers on the way score higher; ones that would make you late are skipped.")
    }

    /// Current goal if set and not more than 30 min past.
    private func goal() -> (name: String, loc: LatLng?, minutesLeft: Double)? {
        guard let name = defaults.string(forKey: "goalName") else { return nil }
        let by = Date(timeIntervalSince1970: defaults.double(forKey: "goalBy"))
        let left = by.timeIntervalSinceNow / 60
        if left < -30 { defaults.removeObject(forKey: "goalName"); return nil }
        return (name, resolve(name), left)
    }

    private func clock(_ d: Date) -> String {
        let f = DateFormatter(); f.dateFormat = "h:mm a"; return f.string(from: d)
    }

    private func handle(_ lines: [OcrLine]) {
        readGoal(lines)
        let parsed = OfferParser.parse(lines)
        if OfferParser.isRecordScreen(lines) {
            // Record screen: Ongoing cards are YOUR jobs. Never score them as offers.
            if OfferParser.hasInProgress(lines) { sync(parsed) }
            status("👀 Record screen · read \(lines.count) lines", "\(onBoard().count) order(s) on board")
            return
        }

        let drops = onBoard().compactMap { resolve($0.drop) }
        let gl = goal()
        var summary: [String] = []
        if let gl = gl { summary.append("🎯 \(gl.name) in \(Int(gl.minutesLeft)) min" + (gl.loc == nil ? " (locating…)" : "")) }
        for o in parsed.prefix(5) {
            guard let p = resolve(o.pickup), let d = resolve(o.drop) else {   // looked up in background; next frame
                summary.append("📍 locating \(o.pickup) → \(o.drop)"); continue
            }
            let s = Scorer.score(pickup: p, drop: d, pickupKm: o.pickupKm ?? 3.0, fare: o.fare, priority: o.priority,
                                 onBoardDrops: drops, target: target, goal: gl?.loc, minutesLeft: gl?.loc == nil ? nil : gl?.minutesLeft)
            // Your rules: single stop only, pickup within 2.5 km.
            let tooFar = (o.pickupKm ?? 99) > maxPickupKm
            let multi = o.extraStops > 0
            let icon = multi ? "🚫 multi-stop" : (tooFar ? "🚫 \(one(o.pickupKm ?? 0)) km away" :
                       (s.verdict == .take ? "✅" : (s.verdict == .ok ? "🟡" : (s.verdict == .late ? "⏰ late" : "⛔"))))
            summary.append("\(icon) \(Int(s.perHour))/hr \(o.pickup) → \(o.drop) (\(money(o.fare)))")
            if notified.contains(o.key) { continue }
            notified.insert(o.key)
            guard s.verdict == .take || s.verdict == .ok, !tooFar, !multi else { continue }
            let label = s.verdict == .take ? "✅ TAKE" : "🟡 OK"
            // When this order would be delivered: pickup, then the best order through your on-board drops.
            let deliverMin = drops.isEmpty
                ? Scorer.minutes((o.pickupKm ?? 3.0) + Geo.km(p, d), stops: 2)
                : Scorer.minutes((o.pickupKm ?? 3.0) + Scorer.routeKm(from: p, through: drops + [d]), stops: 2 + drops.count)
            let doneAt = clock(Date().addingTimeInterval(deliverMin * 60))
            let title = "\(label) · +\(money(s.netGain)) net · done ~\(doneAt)"
            let pool = (o.type != nil && o.type!.lowercased() != "regular") ? " · \(o.type!)" : ""
            let fareText = "Fare \(money(o.fare))" + (o.priority > 0 ? " + \(money(o.priority)) priority" : "")
            let body = "\(o.pickup) → \(o.drop)\(pool)\n\(fareText) · pickup \(one(s.pickupKm)) km · ~\(Int(deliverMin)) min · \(Int(s.perHour))/hr" +
                (drops.isEmpty ? "" : " · stacks with \(drops.count) on board") +
                (s.spareMin.map { " · \(Int($0)) min spare for \(gl?.name ?? "goal")" } ?? "")
            post(id: o.key, title: title, body: body, sound: true)
        }
        status("👀 Read \(lines.count) lines · \(parsed.count) offer\(parsed.count == 1 ? "" : "s")",
               summary.isEmpty ? "No offers recognised on this screen." : summary.joined(separator: "\n"))
    }

    /// Silent entry in Notification Centre (no banner) showing what the reader sees. Updated at most every 10 s.
    private func status(_ title: String, _ body: String) {
        let key = title + body
        guard key != lastStatus, Date().timeIntervalSince(lastStatusAt) >= 10 else { return }
        lastStatus = key; lastStatusAt = Date()
        post(id: "status", title: title, body: body)
    }

    /// Make "on board" match the Ongoing tab.
    private func sync(_ parsed: [ParsedOffer]) {
        let orders = parsed.map { SavedOrder(pickup: $0.pickup, drop: $0.drop, fare: $0.fare, priority: $0.priority, saved: Date()) }
        let old = onBoard().map { "\($0.pickup)|\($0.drop)" }.sorted()
        let new = orders.map { "\($0.pickup)|\($0.drop)" }.sorted()
        if let data = try? JSONEncoder().encode(orders) { defaults.set(data, forKey: "orders") }
        if old != new {
            post(id: "status", title: "📋 Synced \(orders.count) ongoing order\(orders.count == 1 ? "" : "s")",
                 body: "New offers are now scored as stacks on top of these.")
        }
        orders.forEach { _ = resolve($0.pickup); _ = resolve($0.drop) }
    }

    /// Jobs on board, forgotten after 3 hours in case the Ongoing tab isn't revisited.
    private func onBoard() -> [SavedOrder] {
        guard let data = defaults.data(forKey: "orders"),
              let list = try? JSONDecoder().decode([SavedOrder].self, from: data) else { return [] }
        return list.filter { Date().timeIntervalSince($0.saved) < 3 * 3600 }
    }

    // MARK: - Places

    /// Built-in area first, then cached Apple geocoding; unknown names are looked up in the background.
    private func resolve(_ name: String) -> LatLng? {
        if let a = Geo.matchArea(name) { return Geo.areas[a] }
        let key = name.lowercased()
        lock.lock(); defer { lock.unlock() }
        if let c = geoCache[key] { return c }
        if geoFailed.contains(key) || geoPending.contains(key) { return nil }
        geoPending.insert(key)
        DispatchQueue.main.async { [weak self] in
            self?.geocoder.geocodeAddressString("\(name), Singapore") { marks, _ in
                guard let self = self else { return }
                self.lock.lock()
                self.geoPending.remove(key)
                if let loc = marks?.first?.location {
                    let p = LatLng(lat: loc.coordinate.latitude, lng: loc.coordinate.longitude)
                    if Geo.inSingapore(p) { self.geoCache[key] = p } else { self.geoFailed.insert(key) }
                } else { self.geoFailed.insert(key) }
                let snapshot = self.geoCache
                self.lock.unlock()
                if let data = try? JSONEncoder().encode(snapshot) { self.defaults.set(data, forKey: "geo") }
            }
        }
        return nil
    }

    // MARK: - Output

    private func post(id: String, title: String, body: String, sound: Bool = false) {
        let c = UNMutableNotificationContent()
        c.title = title
        c.body = body
        if sound { c.sound = .default }
        c.threadIdentifier = "offers"
        if #available(iOS 15.0, *) { c.interruptionLevel = sound ? .active : .passive }
        let req = UNNotificationRequest(identifier: id, content: c, trigger: nil)
        UNUserNotificationCenter.current().add(req)
    }

    private func money(_ v: Double) -> String { String(format: "$%.2f", v) }
    private func one(_ v: Double) -> String { String(format: "%.1f", v) }
}
