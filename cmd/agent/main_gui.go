//go:build gui || android

package main

import (
	"context"
	"os/exec"
	"regexp"
	"runtime"
	"strings"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/layout"
	"fyne.io/fyne/v2/widget"

	"github.com/nezhahq/agent/model"
	"github.com/nezhahq/agent/pkg/monitor"
)

var (
	isAgentRunning bool
	agentCancel    context.CancelFunc
)

func main() {
	a := app.NewWithID("com.nezhahq.agent")
	w := a.NewWindow("Nezha Agent")

	// Preferences
	prefs := a.Preferences()
	savedServer := prefs.StringWithFallback("server", "")
	savedSecret := prefs.StringWithFallback("secret", "")
	savedTLS := prefs.BoolWithFallback("tls", false)

	// UI Elements
	serverEntry := widget.NewEntry()
	serverEntry.SetText(savedServer)
	serverEntry.SetPlaceHolder("IP:Port or Domain:Port")

	secretEntry := widget.NewEntry()
	secretEntry.SetText(savedSecret)
	secretEntry.SetPlaceHolder("UUID (Client Secret)")

	tlsCheck := widget.NewCheck("Enable TLS", nil)
	tlsCheck.SetChecked(savedTLS)

	scriptEntry := widget.NewMultiLineEntry()
	scriptEntry.SetPlaceHolder("Paste curl installation script here...")
	scriptEntry.Wrapping = fyne.TextWrapWord

	statusLabel := widget.NewLabel("Status: Stopped")

	var startStopBtn *widget.Button
	startStopBtn = widget.NewButton("Start Agent", func() {
		if isAgentRunning {
			// Stop the agent
			if agentCancel != nil {
				agentCancel()
				agentCancel = nil
			}
			isAgentRunning = false
			startStopBtn.SetText("Start Agent")
			statusLabel.SetText("Status: Stopped")
		} else {
			// Save config
			prefs.SetString("server", serverEntry.Text)
			prefs.SetString("secret", secretEntry.Text)
			prefs.SetBool("tls", tlsCheck.Checked)

			// Set global agent config
			agentConfig = model.AgentConfig{
				Server:       serverEntry.Text,
				ClientSecret: secretEntry.Text,
				UUID:         secretEntry.Text,
				TLS:          tlsCheck.Checked,
				DisableCommandExecute: true, // Default safe on mobile
			}
			
			// Optional: Check root and enable command execute if rooted on Android
			if runtime.GOOS == "android" {
				if err := exec.Command("su", "-c", "echo root").Run(); err == nil {
					agentConfig.DisableCommandExecute = false
				}
			}

			// PreRun Setup
			setEnv()
			monitor.InitConfig(&agentConfig)
			initialized = false

			// Start Agent with Context
			ctx, cancel := context.WithCancel(context.Background())
			agentCancel = cancel
			go run(ctx)

			isAgentRunning = true
			startStopBtn.SetText("Stop Agent")
			statusLabel.SetText("Status: Running")
		}
	})

	parseBtn := widget.NewButton("Auto Fill from Script", func() {
		script := scriptEntry.Text
		if script == "" {
			dialog.ShowInformation("Empty", "Please paste the curl script first.", w)
			return
		}
		
		reServer := regexp.MustCompile(`NZ_SERVER=([\w\.:-]+)`)
		reSecret := regexp.MustCompile(`NZ_CLIENT_SECRET=([\w\-]+)`)
		reTLS := regexp.MustCompile(`NZ_TLS=(true|false)`)

		mServer := reServer.FindStringSubmatch(script)
		if len(mServer) > 1 {
			serverEntry.SetText(mServer[1])
		}

		mSecret := reSecret.FindStringSubmatch(script)
		if len(mSecret) > 1 {
			secretEntry.SetText(mSecret[1])
		}

		mTLS := reTLS.FindStringSubmatch(script)
		if len(mTLS) > 1 {
			tlsCheck.SetChecked(strings.ToLower(mTLS[1]) == "true")
		}
		
		dialog.ShowInformation("Success", "Fields populated from script", w)
	})

	form := container.NewVBox(
		widget.NewLabelWithStyle("One-Click Setup", fyne.TextAlignLeading, fyne.TextStyle{Bold: true}),
		scriptEntry,
		parseBtn,
		widget.NewSeparator(),
		widget.NewLabelWithStyle("Manual Configuration", fyne.TextAlignLeading, fyne.TextStyle{Bold: true}),
		widget.NewLabel("Server:"),
		serverEntry,
		widget.NewLabel("Client Secret (UUID):"),
		secretEntry,
		tlsCheck,
		layout.NewSpacer(),
		statusLabel,
		startStopBtn,
	)

	w.SetContent(container.NewPadded(form))
	w.Resize(fyne.NewSize(400, 600))
	w.ShowAndRun()
}
