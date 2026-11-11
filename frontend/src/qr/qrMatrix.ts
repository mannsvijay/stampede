import qrcode from 'qrcode-generator'

/**
 * The QR code of a ticket string as a square grid of modules (true = dark), row by row.
 *
 * - Byte mode, as docs/ticket-format.md says. (The string is base64url with lowercase letters, so
 *   the denser alphanumeric mode, which has uppercase only, couldn't hold it anyway.)
 * - Error correction level M: about 15% of the code can be damaged or dirty and it still scans.
 * - Version (size) chosen automatically: the smallest that fits. A 195-character ticket needs
 *   version 10, which is 17 + 4 × 10 = 57 modules per side (D-018).
 */
export function qrModules(text: string): boolean[][] {
  const qr = qrcode(0, 'M')
  qr.addData(text, 'Byte')
  qr.make()
  const size = qr.getModuleCount()
  return Array.from({ length: size }, (_, row) => Array.from({ length: size }, (_, col) => qr.isDark(row, col)))
}
