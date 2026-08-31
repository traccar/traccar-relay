# Traccar Relay

## Overview

Traccar Relay is an Android app that relays location reports from Google's Find My Device network (Bluetooth trackers, tags, and other supported devices linked to your Google account) into your own [Traccar](https://www.traccar.org) server.

After signing in with your Google account, the app lists the devices registered on your Find My Device network, decrypts their encrypted location reports, and forwards each position update to a Traccar server URL you provide, using Traccar's [OsmAnd protocol](https://www.traccar.org/osmand/). Updates are delivered in the background via push notifications, so the app doesn't need to be kept open.

This lets you track any Find My Device-compatible tag or accessory in your own self-hosted Traccar instance, alongside your other GPS devices.

## Setup

1. Install and open the app.
2. Sign in with the Google account linked to your Find My Device network.
3. Enter your Traccar server URL (the OsmAnd protocol endpoint, e.g. `http://your-server:5055`).
4. Select the devices you want to relay.

See the [Traccar documentation](https://www.traccar.org/documentation/) for how to set up a server if you don't have one yet.

## Build

Standard Android Studio / Gradle project:

```shell
./gradlew assembleDebug
```

## License

Apache License, Version 2.0. See [LICENSE.txt](https://github.com/traccar/traccar-relay/blob/master/LICENSE.txt) for details.
