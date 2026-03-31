# Git Workflow for nrepl-bridge

## Your repo structure

There is one branch: `master`. Every new commit moves `master` forward.
Tags are sticky notes on specific commits. They don't move.

```
0009781  fix: stale port files            <-- HEAD (where you are)
4e291c3  fix: 6 code issues              <-- template/v3 (sticky note)
0be4b0b  fix: etaoin deps                <-- template/v2 (sticky note)
5f11b6f  golden image v1                 <-- template/v1 (sticky note)
```

When someone runs `git checkout template/v3`, they get commit `4e291c3` --
exactly that state of the code, frozen. HEAD moves on, the tag stays put.

## Daily workflow

Work on `master`. Edit files, commit, repeat. Tags don't interfere.
You never need to switch branches.

## When to make a new tag

When you're happy with the state of the code and want to say "this is a
release that people can clone from":

```
git tag template/v4
```

One command. It sticks a note on whatever commit you're at right now.
Then update the version string in `CLAUDE.md` and `ACTIVATION.md` to match.

## Rules

- **Tags are permanent.** Don't move them unless nobody has cloned from
  them yet.
- **Never delete a tag** that someone might have cloned from. They'd have
  a version string that no longer exists.
- **Update CLAUDE.md and ACTIVATION.md** version strings when you tag.
- **HEAD can be ahead of the latest tag.** That's normal -- it means you
  have unreleased work.

## Useful commands

```bash
# See where you are (commits + tags)
git log --oneline --decorate -10

# List all tags
git tag -l

# See what changed since the last tag
git log template/v3..HEAD --oneline

# Create a new tag at current HEAD
git tag template/v4
```

## What you can't mess up

- Committing doesn't affect tags
- Tags don't affect your working code
- You can always see where you are with `git log`

## Scary operations (avoid unless you know why)

- `git tag -d` -- deletes a tag
- `git reset --hard` -- throws away commits
- `git push --force` -- overwrites remote history
- `git checkout <tag>` -- detaches HEAD (fine for reading, not for working)

You won't need any of these in normal workflow. If you think you do, ask first.
