import fs from 'node:fs';
import path from 'node:path';

/** Production Java roots of the core and the sibling Gradle projects; never build/test outputs. */
export function javaSourceRoots(repo) {
  const platform = path.join(repo, 'platform');
  if (!fs.existsSync(platform)) return [];
  const projects = [platform, ...fs.readdirSync(platform, { withFileTypes: true })
    .filter(entry => entry.isDirectory())
    .map(entry => path.join(platform, entry.name))
    .filter(directory => fs.existsSync(path.join(directory, 'build.gradle')) || fs.existsSync(path.join(directory, 'build.gradle.kts')))];
  return projects.map(project => path.join(project, 'src/main/java')).filter(directory => fs.existsSync(directory));
}
