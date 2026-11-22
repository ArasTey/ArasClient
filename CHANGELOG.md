# Changelog

Notable user-facing changes in each release. The full commit history is
on GitHub; CI turns the matching section here into the release notes
when a version is tagged.

## 1.9.0

### New
- Four app themes, picked during onboarding and switchable in Settings
- First-run coach-mark guide that spotlights the real controls instead of text pages
- Jalali (Persian) dates for subscription expiry

### Improved
- Subscription tab rail and two-column config cards rebuilt in the PattNG style
- Ping results are color-coded by delay; tap the stop icon to cancel a running test
- Bottom bar text shrinks to fit instead of truncating
- Unified corner radius across cards and bars; cleaner menus and insets
- Inter + Dastkhat typography

### Under the hood
- New xray core (patterniha v26.11.21) with Serverless-for-Iran v62 support
- Rebuilt native libraries: libaether 2.4.0 and the hev socks tunnel 2.20.0, with the JNI package-name fix and a hardened tun2socks start that can no longer kill the VPN process
- The whole release is now compiled and signed by GitHub Actions from pinned sources

## 1.8.97

### New
- Real disconnected ping: socket RTT to the server, not a guess
- IKEv2 CA certificate support and ikev2:// subscription import fixes

### Fixed
- Announcement banner hidden when a subscription carried both volume and time
- Announcement toggle showing the same state on and off
- "Used · Unlimited" display for subscriptions with total=0
