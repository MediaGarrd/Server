# Adding a new service

The following is the process for adding support for a new service.

## Open an issue
Go the [issues page](https://github.com/MediaGarrd/Server/issues) and use the template for `New Service Request`.

## Contributing
If there is an existing issue for the service you want and it has a `Status: Approved` label on it, a PR can be opened. Be sure to read the [CONTRIBUTING.md](../../CONTRIBUTING.md) guide before starting any changes.

### Implementation
When adding a new service to the project, doing the following will make it easy:
```bash
rg //@ADD_NEW_SERVICE
```
in the repository root to identify the files that require changes to support a new service. These files will always require modification when adding a new service, but there may be more to do depending on the implementation.
