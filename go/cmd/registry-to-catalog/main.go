package main

import (
	"flag"
	"fmt"
	"io"
	"os"

	transformer "github.com/slimslenderslacks/catalogs"
)

func main() {
	inputFile := flag.String("input", "", "Input community registry JSON file (or - for stdin)")
	outputFile := flag.String("output", "", "Output catalog JSON file (or - for stdout)")
	flag.Parse()

	// Read input
	var inputJSON string
	var err error

	if *inputFile == "" || *inputFile == "-" {
		// Read from stdin
		bytes, err := io.ReadAll(os.Stdin)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error reading from stdin: %v\n", err)
			os.Exit(1)
		}
		inputJSON = string(bytes)
	} else {
		// Read from file
		bytes, err := os.ReadFile(*inputFile)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error reading file %s: %v\n", *inputFile, err)
			os.Exit(1)
		}
		inputJSON = string(bytes)
	}

	// Transform
	catalogJSON, err := transformer.TransformJSON(inputJSON)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error transforming JSON: %v\n", err)
		os.Exit(1)
	}

	// Write output
	if *outputFile == "" || *outputFile == "-" {
		// Write to stdout
		fmt.Println(catalogJSON)
	} else {
		// Write to file
		err := os.WriteFile(*outputFile, []byte(catalogJSON), 0644)
		if err != nil {
			fmt.Fprintf(os.Stderr, "Error writing file %s: %v\n", *outputFile, err)
			os.Exit(1)
		}
		fmt.Fprintf(os.Stderr, "Successfully wrote catalog to %s\n", *outputFile)
	}
}
