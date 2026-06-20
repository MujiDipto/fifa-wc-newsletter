# Contributing

## Prerequisites

- Java 21+
- Python 3.11+
- Maven 3.8+

## Local setup

```bash
git clone https://github.com/MujiDipto/fifa-wc-newsletter.git
cd fifa-wc-newsletter
cp .env.example .env
# Fill in .env with your API keys
```

Start the Python composer:
```bash
cd python-composer
python3.11 -m venv venv && source venv/bin/activate
pip install -r requirements.txt
uvicorn main:app --host 127.0.0.1 --port 8000
```

Build and run the pipeline:
```bash
mvn package -DskipTests
java -jar target/worldcup-newsletter-1.0-SNAPSHOT.jar
```

## Running tests

```bash
mvn test
```

## Branch and PR workflow

- Branch off `main` for all changes
- Keep PRs focused — one concern per PR
- CI must pass before merging
- Never commit `.env`, `*.db`, or any credentials

## Code style

- Java: standard formatting, no wildcard imports
- Python: `ruff`-clean (`ruff check python-composer/`)
