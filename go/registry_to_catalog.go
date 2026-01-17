package catalogs

import (
	"encoding/json"
	"fmt"
	"strings"
)

// Community Registry Structs

type Input struct {
	Description string   `json:"description,omitempty"`
	IsRequired  bool     `json:"isRequired,omitempty"`
	Format      string   `json:"format,omitempty"` // string, number, boolean, filepath
	Value       string   `json:"value,omitempty"`
	IsSecret    bool     `json:"isSecret,omitempty"`
	Default     string   `json:"default,omitempty"`
	Choices     []string `json:"choices,omitempty"`
	Placeholder string   `json:"placeholder,omitempty"`
	Variables   map[string]Input `json:"variables,omitempty"`
}

type KeyValueInput struct {
	Name string `json:"name"`
	Input
}

type Argument struct {
	Type       string           `json:"type"` // named or positional
	Name       string           `json:"name,omitempty"`
	Value      string           `json:"value,omitempty"`
	ValueHint  string           `json:"valueHint,omitempty"`
	IsRepeated bool             `json:"isRepeated,omitempty"`
	Variables  map[string]Input `json:"variables,omitempty"`
	Input
}

type Transport struct {
	Type    string            `json:"type"` // stdio, sse, streamable-http
	URL     string            `json:"url,omitempty"`
	Headers []KeyValueInput   `json:"headers,omitempty"`
}

type Package struct {
	RegistryType         string           `json:"registryType"` // npm, pypi, oci, nuget, mcpb
	Identifier           string           `json:"identifier"`
	Version              string           `json:"version"`
	Transport            Transport        `json:"transport"`
	RegistryBaseURL      string           `json:"registryBaseUrl,omitempty"`
	RuntimeHint          string           `json:"runtimeHint,omitempty"`
	FileSha256           string           `json:"fileSha256,omitempty"`
	RuntimeArguments     []Argument       `json:"runtimeArguments,omitempty"`
	PackageArguments     []Argument       `json:"packageArguments,omitempty"`
	EnvironmentVariables []KeyValueInput  `json:"environmentVariables,omitempty"`
}

type Icon struct {
	Src      string   `json:"src"`
	Theme    string   `json:"theme,omitempty"`    // light or dark
	MimeType string   `json:"mimeType,omitempty"` // image/png, image/jpeg, etc
	Sizes    []string `json:"sizes,omitempty"`
}

type ServerDetail struct {
	Schema      string            `json:"$schema,omitempty"`
	Name        string            `json:"name"`
	Description string            `json:"description"`
	Title       string            `json:"title,omitempty"`
	Version     string            `json:"version"`
	WebsiteURL  string            `json:"websiteUrl,omitempty"`
	Packages    []Package         `json:"packages,omitempty"`
	Remotes     []Transport       `json:"remotes,omitempty"`
	Icons       []Icon            `json:"icons,omitempty"`
	Meta        map[string]interface{} `json:"_meta,omitempty"`
}

type RegistryEntry struct {
	Server ServerDetail `json:"server"`
}

// Docker Catalog Structs

type ConfigSchema struct {
	Name        string                 `json:"name"`
	Type        string                 `json:"type"`
	Description string                 `json:"description"`
	Properties  map[string]ConfigProperty `json:"properties"`
	Required    []string               `json:"required,omitempty"`
}

type ConfigProperty struct {
	Type        string `json:"type"`
	Description string `json:"description,omitempty"`
}

type Secret struct {
	Name    string `json:"name"`
	Env     string `json:"env"`
	Example string `json:"example,omitempty"`
}

type EnvVar struct {
	Name  string `json:"name"`
	Value string `json:"value"`
}

type DockerRemote struct {
	URL           string            `json:"url"`
	TransportType string            `json:"transport_type"`
	Headers       map[string]string `json:"headers,omitempty"`
}

type DockerServer struct {
	Name        string          `json:"name"`
	Title       string          `json:"title,omitempty"`
	Description string          `json:"description"`
	Image       string          `json:"image,omitempty"`
	Remote      *DockerRemote   `json:"remote,omitempty"`
	Type        string          `json:"type,omitempty"`
	Config      []ConfigSchema  `json:"config,omitempty"`
	Secrets     []Secret        `json:"secrets,omitempty"`
	Env         []EnvVar        `json:"env,omitempty"`
	Command     []string        `json:"command,omitempty"`
	User        string          `json:"user,omitempty"`
	Volumes     []string        `json:"volumes,omitempty"`
	Icon        string          `json:"icon,omitempty"`
	OAuth       interface{}     `json:"oauth,omitempty"`
}

// Helper Functions

func extractServerName(fullName string) string {
	// com.docker.mcp/server-name -> com-docker-mcp-server-name
	name := strings.ReplaceAll(fullName, "/", "-")
	name = strings.ReplaceAll(name, ".", "-")
	return name
}

func collectVariables(serverDetail ServerDetail) map[string]Input {
	variables := make(map[string]Input)

	// Collect from packages
	for _, pkg := range serverDetail.Packages {
		// From package arguments
		for _, arg := range pkg.PackageArguments {
			for k, v := range arg.Variables {
				variables[k] = v
			}
		}
		// From runtime arguments
		for _, arg := range pkg.RuntimeArguments {
			for k, v := range arg.Variables {
				variables[k] = v
			}
		}
		// From environment variables
		for _, envVar := range pkg.EnvironmentVariables {
			// Check if the env var has nested variables (interpolation case)
			for k, v := range envVar.Variables {
				variables[k] = v
			}
			// Also check if the env var itself is a direct secret/config
			// (no value, just a declaration with isSecret/isRequired)
			if envVar.Value == "" && (envVar.IsSecret || envVar.IsRequired || envVar.Description != "") {
				variables[envVar.Name] = envVar.Input
			}
		}
	}

	// Collect from remotes
	for _, remote := range serverDetail.Remotes {
		for _, header := range remote.Headers {
			for k, v := range header.Variables {
				variables[k] = v
			}
		}
	}

	return variables
}

func separateSecretsAndConfig(variables map[string]Input) (secrets map[string]Input, config map[string]Input) {
	secrets = make(map[string]Input)
	config = make(map[string]Input)

	for k, v := range variables {
		if v.IsSecret {
			secrets[k] = v
		} else {
			config[k] = v
		}
	}

	return secrets, config
}

func buildConfigSchema(configVars map[string]Input, serverName string) []ConfigSchema {
	if len(configVars) == 0 {
		return nil
	}

	properties := make(map[string]ConfigProperty)
	var required []string

	for varName, varDef := range configVars {
		jsonType := "string"
		switch varDef.Format {
		case "number":
			jsonType = "number"
		case "boolean":
			jsonType = "boolean"
		}

		properties[varName] = ConfigProperty{
			Type:        jsonType,
			Description: varDef.Description,
		}

		if varDef.IsRequired {
			required = append(required, varName)
		}
	}

	return []ConfigSchema{{
		Name:        serverName,
		Type:        "object",
		Description: fmt.Sprintf("Configuration for %s", serverName),
		Properties:  properties,
		Required:    required,
	}}
}

func buildSecrets(serverName string, secretVars map[string]Input) []Secret {
	var secrets []Secret

	for varName, varDef := range secretVars {
		secret := Secret{
			Name: fmt.Sprintf("%s.%s", serverName, varName),
			Env:  strings.ToUpper(varName),
		}

		if varDef.Placeholder != "" {
			secret.Example = varDef.Placeholder
		}

		secrets = append(secrets, secret)
	}

	return secrets
}

func extractImageInfo(pkg Package) string {
	if pkg.RegistryType == "oci" && pkg.Transport.Type == "stdio" {
		return fmt.Sprintf("%s@%s", pkg.Identifier, pkg.Version)
	}
	return ""
}

func restoreInterpolatedValue(processedValue string, variables map[string]Input) string {
	result := processedValue

	// Replace {varName} with {{varName}} for config vars or ${VARNAME} for secrets
	for varName, varDef := range variables {
		placeholder := fmt.Sprintf("{%s}", varName)
		var replacement string
		if varDef.IsSecret {
			replacement = fmt.Sprintf("${%s}", strings.ToUpper(varName))
		} else {
			replacement = fmt.Sprintf("{{%s}}", varName)
		}
		result = strings.ReplaceAll(result, placeholder, replacement)
	}

	return result
}

func convertEnvVariables(envVars []KeyValueInput, configVars map[string]Input, serverName string) []EnvVar {
	if len(envVars) == 0 {
		return nil
	}

	var result []EnvVar
	for _, ev := range envVars {
		// Skip direct secret env vars - they should only be in secrets array
		if ev.IsSecret {
			continue
		}

		value := ev.Value
		if len(ev.Variables) > 0 {
			// If there are nested variables, restore interpolation
			value = restoreInterpolatedValue(value, ev.Variables)
		} else if value == "" {
			// Check if this env var is defined as a config variable
			if _, isConfig := configVars[ev.Name]; isConfig {
				// Use fully qualified interpolation syntax to reference the config variable
				value = fmt.Sprintf("{{%s.%s}}", serverName, ev.Name)
			} else if ev.Default != "" {
				// Otherwise use the default value
				value = ev.Default
			}
		}

		result = append(result, EnvVar{
			Name:  ev.Name,
			Value: value,
		})
	}

	return result
}

func parseRuntimeArg(arg Argument) string {
	value := arg.Value
	if len(arg.Variables) > 0 {
		value = restoreInterpolatedValue(value, arg.Variables)
	}

	if arg.Type == "named" {
		return fmt.Sprintf("%s=%s", arg.Name, value)
	}
	return value
}

func extractUserFromRuntimeArgs(runtimeArgs []Argument) string {
	for _, arg := range runtimeArgs {
		if arg.Type == "named" && arg.Name == "-u" {
			value := arg.Value
			if len(arg.Variables) > 0 {
				value = restoreInterpolatedValue(value, arg.Variables)
			}
			// Extract value after '='
			parts := strings.SplitN(value, "=", 2)
			if len(parts) == 2 {
				return parts[1]
			}
			return value
		}
	}
	return ""
}

func extractVolumesFromRuntimeArgs(runtimeArgs []Argument) []string {
	var volumes []string

	for _, arg := range runtimeArgs {
		if arg.Type != "named" {
			continue
		}

		value := arg.Value
		if len(arg.Variables) > 0 {
			value = restoreInterpolatedValue(value, arg.Variables)
		}

		if arg.Name == "--mount" {
			// For --mount, parse and convert to simple src:dst format
			// Input: "type=bind,src={{source_path}},dst={{target_path}}"
			// Output: "{{source_path}}:{{target_path}}"
			var src, dst string
			parts := strings.Split(value, ",")
			for _, part := range parts {
				kv := strings.SplitN(part, "=", 2)
				if len(kv) == 2 {
					switch kv[0] {
					case "src", "source":
						src = kv[1]
					case "dst", "destination", "target":
						dst = kv[1]
					}
				}
			}
			if src != "" && dst != "" {
				volumes = append(volumes, fmt.Sprintf("%s:%s", src, dst))
			} else {
				// Fallback to full value if parsing fails
				volumes = append(volumes, value)
			}
		} else if arg.Name == "-v" {
			// For -v, extract value after '=' if present
			parts := strings.SplitN(value, "=", 2)
			if len(parts) == 2 {
				volumes = append(volumes, parts[1])
			} else {
				volumes = append(volumes, value)
			}
		}
	}

	return volumes
}

func convertPackageArgsToCommand(packageArgs []Argument) []string {
	if len(packageArgs) == 0 {
		return nil
	}

	var command []string
	for _, arg := range packageArgs {
		command = append(command, parseRuntimeArg(arg))
	}

	return command
}

func convertRemote(remote Transport) *DockerRemote {
	dockerRemote := &DockerRemote{
		URL:           remote.URL,
		TransportType: remote.Type,
	}

	if len(remote.Headers) > 0 {
		headers := make(map[string]string)
		for _, header := range remote.Headers {
			value := header.Value
			if len(header.Variables) > 0 {
				value = restoreInterpolatedValue(value, header.Variables)
			}
			headers[header.Name] = value
		}
		dockerRemote.Headers = headers
	}

	return dockerRemote
}

func getPublisherProvidedMeta(meta map[string]interface{}) map[string]interface{} {
	if meta == nil {
		return nil
	}

	if ppData, ok := meta["io.modelcontextprotocol.registry/publisher-provided"]; ok {
		if ppMap, ok := ppData.(map[string]interface{}); ok {
			return ppMap
		}
	}

	return nil
}

// TransformToDocker transforms a ServerDetail (community format) to DockerServer (catalog format)
func TransformToDocker(serverDetail ServerDetail) (*DockerServer, error) {
	serverName := extractServerName(serverDetail.Name)

	var pkg *Package
	if len(serverDetail.Packages) > 0 {
		pkg = &serverDetail.Packages[0]
	}

	var remote *Transport
	if len(serverDetail.Remotes) > 0 {
		remote = &serverDetail.Remotes[0]
	}

	variables := collectVariables(serverDetail)
	secretVars, configVars := separateSecretsAndConfig(variables)

	dockerServer := &DockerServer{
		Name:        serverName,
		Title:       serverDetail.Title,
		Description: serverDetail.Description,
	}

	// Add image if it's an OCI package
	if pkg != nil {
		if image := extractImageInfo(*pkg); image != "" {
			dockerServer.Image = image
			dockerServer.Type = "server"
		}
	}

	// Add remote if present
	if remote != nil {
		dockerServer.Remote = convertRemote(*remote)
		dockerServer.Type = "remote"
	}

	// Add config schema if we have config variables
	if len(configVars) > 0 {
		dockerServer.Config = buildConfigSchema(configVars, serverName)
	}

	// Add secrets if we have secret variables
	if len(secretVars) > 0 {
		dockerServer.Secrets = buildSecrets(serverName, secretVars)
	}

	// Add environment variables
	if pkg != nil && len(pkg.EnvironmentVariables) > 0 {
		dockerServer.Env = convertEnvVariables(pkg.EnvironmentVariables, configVars, serverName)
	}

	// Add command from package arguments
	if pkg != nil && len(pkg.PackageArguments) > 0 {
		dockerServer.Command = convertPackageArgsToCommand(pkg.PackageArguments)
	}

	// Add user from runtime arguments
	if pkg != nil {
		if user := extractUserFromRuntimeArgs(pkg.RuntimeArguments); user != "" {
			dockerServer.User = user
		}
	}

	// Add volumes from runtime arguments
	if pkg != nil {
		if volumes := extractVolumesFromRuntimeArgs(pkg.RuntimeArguments); len(volumes) > 0 {
			dockerServer.Volumes = volumes
		}
	}

	// Add metadata from publisher-provided
	if publisherMeta := getPublisherProvidedMeta(serverDetail.Meta); publisherMeta != nil {
		if oauth, ok := publisherMeta["oauth"]; ok {
			dockerServer.OAuth = oauth
		}
	}

	// Add icon
	if len(serverDetail.Icons) > 0 {
		dockerServer.Icon = serverDetail.Icons[0].Src
	}

	return dockerServer, nil
}

// TransformJSON transforms community registry JSON to catalog JSON
func TransformJSON(registryJSON string) (string, error) {
	var registryEntry RegistryEntry

	if err := json.Unmarshal([]byte(registryJSON), &registryEntry); err != nil {
		return "", fmt.Errorf("failed to parse registry JSON: %w", err)
	}

	dockerServer, err := TransformToDocker(registryEntry.Server)
	if err != nil {
		return "", fmt.Errorf("failed to transform: %w", err)
	}

	catalogJSON, err := json.MarshalIndent(dockerServer, "", "  ")
	if err != nil {
		return "", fmt.Errorf("failed to marshal catalog JSON: %w", err)
	}

	return string(catalogJSON), nil
}

