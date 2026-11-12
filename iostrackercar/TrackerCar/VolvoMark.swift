import SwiftUI

/// Szkicowy znaczek w stylu „iron mark": okrąg, strzałka w prawo-górę i poprzeczka z napisem.
/// Rysowany ręcznie z lekkim „drżeniem" linii i podwójnym obrysem, jak ołówkiem.

private struct WobbleCircle: Shape {
    var seed: Double
    func path(in rect: CGRect) -> Path {
        let c = CGPoint(x: rect.midX, y: rect.midY)
        let r = min(rect.width, rect.height) / 2
        var p = Path()
        let n = 80
        for i in 0...(n + 7) {                       // lekki „nawrót" na końcu okręgu
            let t = Double(i) / Double(n) * 2 * .pi + seed
            let w = 1 + 0.014 * sin(3 * t + seed * 5) + 0.009 * sin(7 * t + seed * 3) + Double(i > n ? 0.012 : 0)
            let pt = CGPoint(x: c.x + r * w * cos(t), y: c.y + r * w * sin(t))
            i == 0 ? p.move(to: pt) : p.addLine(to: pt)
        }
        return p
    }
}

private struct SketchArrow: Shape {
    var jitter: CGFloat
    func path(in rect: CGRect) -> Path {
        let c = CGPoint(x: rect.midX, y: rect.midY)
        let r = rect.width * 0.39
        let a = CGFloat.pi / 4
        let dir = CGPoint(x: cos(a), y: -sin(a))
        let p0 = CGPoint(x: c.x + dir.x * (r - 2), y: c.y + dir.y * (r - 2))
        let tip = CGPoint(x: c.x + dir.x * (r + rect.width * 0.17) + jitter,
                          y: c.y + dir.y * (r + rect.width * 0.17) - jitter)
        let h = rect.width * 0.1
        var p = Path()
        p.move(to: p0); p.addLine(to: tip)
        p.move(to: CGPoint(x: tip.x - h, y: tip.y)); p.addLine(to: tip)
        p.addLine(to: CGPoint(x: tip.x, y: tip.y + h))
        return p
    }
}

private struct SketchBand: Shape {
    var jitter: CGFloat
    func path(in rect: CGRect) -> Path {
        var p = Path()
        let w = rect.width * 0.78, h = rect.height * 0.2
        let x0 = rect.midX - w / 2, y0 = rect.midY - h / 2
        p.move(to: CGPoint(x: x0 + jitter, y: y0))
        p.addLine(to: CGPoint(x: x0 + w, y: y0 + jitter))
        p.addLine(to: CGPoint(x: x0 + w - jitter, y: y0 + h))
        p.addLine(to: CGPoint(x: x0, y: y0 + h - jitter))
        p.addLine(to: CGPoint(x: x0 + jitter, y: y0 - 1))
        return p
    }
}

struct VolvoMark: View {
    let label: String
    let isOn: Bool
    var spin: Double = 0

    var body: some View {
        let main = isOn ? Theme.accent : Theme.ink
        GeometryReader { geo in
            let s = geo.size.width
            let d = s * 0.78
            ZStack {
                Circle().fill((isOn ? Theme.accent : Theme.ink).opacity(isOn ? 0.22 : 0.06)).blur(radius: 28)
                // pierścień – dwa przejścia ołówka
                ZStack {
                    WobbleCircle(seed: 0.3).stroke(main, style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
                    WobbleCircle(seed: 1.9).stroke(main.opacity(0.5), style: StrokeStyle(lineWidth: 1.6, lineCap: .round))
                        .scaleEffect(1.025)
                }
                .frame(width: d, height: d)
                .rotationEffect(.degrees(spin))
                // strzałka
                SketchArrow(jitter: 0).stroke(main, style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
                SketchArrow(jitter: 2).stroke(main.opacity(0.5), style: StrokeStyle(lineWidth: 1.6, lineCap: .round))
                // poprzeczka
                SketchBand(jitter: 0).fill(isOn ? Theme.ink : Theme.accent)
                SketchBand(jitter: 0).stroke(main, style: StrokeStyle(lineWidth: 3, lineJoin: .round))
                SketchBand(jitter: 2.5).stroke(main.opacity(0.5), style: StrokeStyle(lineWidth: 1.2))
                Text(label)
                    .font(Theme.f(s * 0.115, .bold)).tracking(4)
                    .foregroundStyle(isOn ? Theme.bg : Theme.ink)
            }
            .frame(width: s, height: s)
        }
        .aspectRatio(1, contentMode: .fit)
    }
}
