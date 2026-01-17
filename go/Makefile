.PHONY: all build test clean install

# Default target
all: build

# Build the CLI binary
build:
	@echo "Building registry-to-catalog CLI..."
	@go build -o bin/registry-to-catalog ./cmd/registry-to-catalog
	@echo "Binary built at: bin/registry-to-catalog"

# Run tests
test:
	@echo "Running tests..."
	@go test -v

# Run tests with coverage
test-coverage:
	@echo "Running tests with coverage..."
	@go test -v -coverprofile=coverage.out
	@go tool cover -html=coverage.out -o coverage.html
	@echo "Coverage report generated at: coverage.html"

# Clean build artifacts
clean:
	@echo "Cleaning build artifacts..."
	@rm -rf bin/
	@rm -f coverage.out coverage.html
	@echo "Clean complete"

# Install the CLI binary to GOPATH/bin
install:
	@echo "Installing registry-to-catalog to GOPATH/bin..."
	@go install ./cmd/registry-to-catalog
	@echo "Installation complete"

# Format code
fmt:
	@echo "Formatting code..."
	@go fmt ./...

# Run linter
lint:
	@echo "Running linter..."
	@go vet ./...

# Example: transform a registry file
example:
	@echo "Transforming example file..."
	@./bin/registry-to-catalog -input ../servers/grounding_lite.json

# Show help
help:
	@echo "Available targets:"
	@echo "  make build          - Build the CLI binary"
	@echo "  make test           - Run tests"
	@echo "  make test-coverage  - Run tests with coverage report"
	@echo "  make clean          - Remove build artifacts"
	@echo "  make install        - Install CLI to GOPATH/bin"
	@echo "  make fmt            - Format code"
	@echo "  make lint           - Run linter"
	@echo "  make example        - Run transformation on example file"
	@echo "  make help           - Show this help message"
