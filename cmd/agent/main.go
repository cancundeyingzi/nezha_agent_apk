//go:build !(gui || android)

package main

func main() {
	runCLI()
}
