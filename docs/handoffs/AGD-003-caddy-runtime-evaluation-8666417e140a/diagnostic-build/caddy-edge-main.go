// This entrypoint is based on Caddy v2.11.7's documented custom-build entrypoint.
// It links only the adapters/modules required by the staging Caddyfile.
package main

import (
	_ "time/tzdata"

	caddycmd "github.com/caddyserver/caddy/v2/cmd"
	_ "github.com/caddyserver/caddy/v2/caddyconfig/caddyfile"
	_ "github.com/caddyserver/caddy/v2/modules/caddyhttp/standard"
	_ "github.com/caddyserver/caddy/v2/modules/caddytls"
)

func main() {
	caddycmd.Main()
}
