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

// Pickup icon read as a letter in front of the place
let icons = OfferParser.parse([l("(~2.4 km) Pick Up Now", 100), l("Regular", 150), l("O Kovan", 200), l("0 Punggol", 250), l("S$14.20", 320, 900)])
check(icons.first?.pickup == "Kovan" && icons.first?.drop == "Punggol", "strips icon letter: \(icons.first.map { $0.pickup + " → " + $0.drop } ?? "none")")

// "+ x.xx": counts only on the fare's row
let split = OfferParser.parse([l("(~2.0 km) Pick Up Now", 100), l("Regular", 150), l("Kovan", 200), l("Punggol", 250),
                               l("S$18.80", 320, 700), l("+ 1.70", 322, 900)])
check(split.first?.priority == 1.7, "split '+ 1.70' on fare row counts")
let stray = OfferParser.parse([l("(~2.0 km) Pick Up Now", 100), l("Regular", 150), l("Kovan", 200), l("+ 3.00", 225, 600), l("Punggol", 250),
                               l("S$19.10", 320, 700)])
check(stray.first?.priority == 0 && stray.first?.fare == 19.1, "stray '+ 3.00' elsewhere ignored")

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

// Goal line from the app, and destination-aware scoring
let g = OfferParser.goal([l("Offer Reader", 50), l("GOAL: Paya Lebar BY 20:00", 120)])
check(g?.place == "Paya Lebar" && g?.hour == 20 && g?.minute == 0, "reads goal line")
check(OfferParser.goal([l("GOAL: NONE", 120)])?.place == "", "reads empty goal")
let A = Geo.areas
let onWay = Scorer.score(pickup: A["Serangoon"]!, drop: A["Eunos"]!, pickupKm: 1.0, fare: 11, priority: 0,
                         onBoardDrops: [], target: 14, goal: A["Paya Lebar"]!, minutesLeft: 60)
let noGoal = Scorer.score(pickup: A["Serangoon"]!, drop: A["Eunos"]!, pickupKm: 1.0, fare: 11, priority: 0,
                          onBoardDrops: [], target: 14)
print("on the way:", onWay.perHour, onWay.spareMin ?? -1, "no goal:", noGoal.perHour)
check(onWay.perHour > noGoal.perHour, "offer heading to goal scores higher")
let late = Scorer.score(pickup: A["Kovan"]!, drop: A["Bukit Permei"]!, pickupKm: 1.0, fare: 15.9, priority: 0,
                        onBoardDrops: [], target: 14, goal: A["Paya Lebar"]!, minutesLeft: 40)
check(late.verdict == .late, "offer that misses the deadline is LATE")

if failures > 0 { print("\(failures) check(s) failed"); exit(1) }
print("All checks passed")
