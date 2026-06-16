# Contributing to OAM

Thanks for your interest in contributing. OAM is a small project with a clear philosophy — contributions that fit that philosophy are welcome from anyone.

---

## Before you start

Read the [README](/README.md) to understand what OAM is and what it isn't. The core principles matter here. Contributions that introduce proprietary scoring, cloud dependencies, or opaque algorithms won't be accepted regardless of their technical quality.

Please also bear in mind I am inexperienced with github and this is my first time building an app. My general policy is move fast and break stuff so patience is appreciated if I'm doing something not usually seen as proper by developers.

---

## Ways to contribute

### LLM Policy

I have no problem with the use of artificial intelligence in the development of this project. In fact it started as a back and forth between myself and Claude that lead to starting the project and it has been the source of almost all the development so far. What matters is the quality of the code and the checks the developer carries out to ensure the code is implemented correctly. I dont care how it's written as long as you take responsibility for it.

### Drivers

Writing a driver to support a new device is the highest-impact contribution you can make. Every driver added means one more wearable freed from its proprietary app.

See the driver authoring guide in the docs folder for the full guide. The short version:

- Drivers are standalone files with an embedded WASM parsing module
- You don't need to modify the OAM app itself
- Submit finished drivers to the [`Driver Builds/`](Driver Builds/) directory via pull request. Create a subdirectory named after your device (e.g. `Driver Builds/Polar H10/`) and place your manifest JSON file inside it.
- Include a brief description of the device and how you tested it

If you're in the process of reverse engineering a device and want input or help, open a Discussion rather than a PR — it's a good place to share findings and get eyes on protocol questions.

### Algorithm improvements

OAM's metric calculations are documented and open. If you believe a calculation can be improved — better methodology, more appropriate defaults, cited research — open an issue first to discuss it before submitting code.

Any change to an algorithm must:
- Be documented in plain language in the relevant docs file
- Include a citation or reasoning for the change
- Not introduce any black-box behaviour

### Android / app code

The app is built in Kotlin with Jetpack Compose. See [`docs/DRIVER_AUTHORING_GUIDE.md`](docs/DRIVER_AUTHORING_GUIDE.md) for the full driver system documentation. A broader architecture overview is planned but not yet written.

Good places to start:
- Open issues labelled `good first issue`
- UI polish and accessibility improvements
- Performance work on the database or background workers
- Test coverage

For larger changes, open an issue to discuss the approach before writing code.

### Documentation

Clear documentation is part of the project's core promise — if users can't understand how a metric is calculated, the open algorithm principle fails. Improvements to clarity, accuracy, or completeness are always welcome.

This includes:
- The driver authoring guide
- Algorithm documentation
- In-app explanatory text
- Translations

---

## Ground rules

**No scores, no coaching language.** OAM does not tell users how recovered they are, how ready they are to train, or how to interpret their data. Don't add features that do this.

**No cloud dependencies.** OAM is local-first by design. Contributions that require a network connection, account, or external service will not be accepted.

**Document your algorithms.** Any metric calculation must be explainable in plain language. If you can't write a sentence describing what a calculation does and why, it doesn't belong in OAM.

**Be direct, not defensive.** In code reviews and discussions, say what you mean. Disagreement is fine; dismissiveness isn't.

---

## Submitting a pull request

1. Fork the repo and create a branch from `main`
2. Make your changes
3. If you've changed any algorithm or added a new metric, update the relevant docs file
4. Open a pull request with a clear description of what you changed and why
5. Link any related issues

Pull requests are reviewed by maintainers as time allows. Small, focused PRs get reviewed faster than large ones.

---

## Reporting issues

Use GitHub Issues for bugs and feature requests. When reporting a bug, include:

- Android version and device model
- Steps to reproduce
- What you expected vs what happened
- Logcat output if relevant

---

## Questions

For general questions, use GitHub Discussions. It's a better format for back-and-forth than issues, and answers become searchable for others.

---

## License

By contributing to OAM, you agree that your contributions will be licensed under the same [MIT License](/LICENSE) that covers the project.
