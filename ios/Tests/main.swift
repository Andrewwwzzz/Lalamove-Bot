import Foundation

// Self-test run by CI before building: screen text from real Lalamove screenshots.
var failures = 0
func check(_ cond: Bool, _ msg: String) { if !cond { print("FAIL: \(msg)"); failures += 1 } else { print("ok: \(msg)") } }
func l(_ t: String, _ y: Int, _ x: Int = 60) -> OcrLine { OcrLine(text: t, left: x, top: y, right: x + 400, bottom: y + 30) }

// Pickup tab, with OCR slips: "~" read as "-", split header, bonus on fare line, "Courier"
let pickup = [l("18:08", 30), l("On Duty", 90), l("New! Heatmap helps you find areas with more orders", 160),
              l("(-16.9 km) Pick Up Now", 250), l("Regular", 320), l("Bukit Merah", 370), l("Novena", 420), l("Car", 480, 900), l("S$18.80 + 1.70", 540, 800),
              l("(~12.6 km)", 640), l("Pick Up Now", 640, 300), l("Regular", 710), l("Cuppage", 760), l("Little India", 810), l("Courier", 870, 900), l("S$9.20", 950, 900),
              l("(~8.1 km) Pick Up Now", 1080), l("Pooling", 1150), l("Kaki Bukit", 1210), l("2 stops", 1240), l("Telok Blangah Heights", 1270),
              l("Order is confirmed....", 1330), l("S$19.30", 1390, 900),
              l("Pickup", 1500), l("Record", 1500, 300)]
let offers = OfferParser.parse(pickup)
print(offers)
check(offers.count == 3, "3 offers on Pickup tab")
check(offers.first?.pickup == "Bukit Merah" && offers.first?.drop == "Novena", "Bukit Merah → Novena")
check(offers.first?.fare == 18.8 && offers.first?.priority == 1.7, "fare 18.80 + 1.70 priority")
check(offers.first?.pickupKm == 16.9, "pickup distance 16.9")
check(offers.count > 2 && offers[2].drop == "Telok Blangah Heights", "multi-stop drop")
check(offers.count > 2 && offers[2].extraStops == 2, "2 stops detected")
check(offers.first?.extraStops == 0, "single stop detected")

// Record → Ongoing
let ongoing = [l("Ongoing", 170), l("Completed", 170, 500), l("Canceled", 170, 900), l("In Progress", 320), l("11.7 km", 440),
               l("# 3632751025968100019", 440, 700), l("Suntec City", 500, 140), l("Bedok", 580, 140), l("S$15.70", 720, 900)]
check(OfferParser.isRecordScreen(ongoing) && OfferParser.hasInProgress(ongoing), "detects Ongoing screen")
check(OfferParser.parse(ongoing).first?.drop == "Bedok", "ongoing order parsed")

// Places and scoring
check(Geo.matchArea("Macpherson Industrial") == "MacPherson", "fuzzy area match")
check(Geo.matchArea("Serangoon North") == "Serangoon North", "longest area name wins")
let s = Scorer.score(pickup: Geo.areas["Science Park"]!, drop: Geo.areas["Springside"]!, pickupKm: 0.5,
                     fare: 22.30, priority: 1.90, onBoardDrops: [], target: 14)
print(s)
check(s.perHour > 14 && s.perHour < 20, "Science Park → Springside ~15/hr")
let (a, b) = Scorer.coefficients(target: 15)
check(abs(a - 1.134) < 0.01 && abs(b - 3.40) < 0.01, "rule 3.40 + 1.13/km at 15/hr")

if failures > 0 { print("\(failures) check(s) failed"); exit(1) }
print("All checks passed")
